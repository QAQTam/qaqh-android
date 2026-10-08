package com.qaqh.harness.data.ringing

import com.qaqh.harness.data.ringing.model.ActivityDelta
import com.qaqh.harness.data.ringing.model.CommandAck
import com.qaqh.harness.data.ringing.model.CommandEnvelope
import com.qaqh.harness.data.ringing.model.EventEnvelope
import com.qaqh.harness.data.ringing.model.InteractionExpired
import com.qaqh.harness.data.ringing.model.InteractionRequested
import com.qaqh.harness.data.ringing.model.InteractionResolved
import com.qaqh.harness.data.ringing.model.RingingProtocol
import com.qaqh.harness.data.ringing.model.payloadData
import com.qaqh.harness.data.ringing.model.payloadKind
import okhttp3.OkHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.util.UUID
import kotlin.math.min

/**
 * 会话级连接生命周期：open → renew 循环 → bootstrap → SSE 事件流，指数退避重连。
 * 参照 qaqh-client（session.rs / v2_stream.rs）的行为：续租间隔 = renew_interval/2，
 * 连续 2 次续租失败即重新 open；SSE 只在拿到 cursor（bootstrap 快照或 reliable 事件）后订阅；
 * reset_required 时回到 bootstrap。
 */
class RingingConnection(
    private val endpoint: String,
    private val token: String,
    /** 目标session_id；null 时只建租约不订阅事件流。可经 [setSessionId] 动态切换。 */
    private var sessionId: String?,
    private val scope: CoroutineScope,
    client: OkHttpClient? = null,
) {
    val api = RingingApi(endpoint, token, client)

    sealed interface State {
        data object Disconnected : State
        data object Connecting : State
        data class Connected(
            val serverEpoch: String,
            val clientSessionId: String,
        ) : State

        data class Failed(
            val reason: String,
            /** 401（token 无效/设备被吊销）：UI 应掉回配对屏，而不是无限重试。 */
            val unauthorized: Boolean = false,
        ) : State
    }

    /** 从 SSE 投影出的会话事件。 */
    sealed interface SessionEvent {
        data class Activity(val state: String, val turnId: String?) : SessionEvent

        /** 待审批请求（kind == permission 时关注）。 */
        data class InteractionRequested(val requested: com.qaqh.harness.data.ringing.model.InteractionRequested) : SessionEvent

        /** 审批已有终局：verdict 为 approved/rejected/answered/dismissed 之一。 */
        data class InteractionResolved(
            val resolved: com.qaqh.harness.data.ringing.model.InteractionResolved,
            val causationCommandId: String?,
        ) : SessionEvent

        data class InteractionExpired(val interactionId: String, val reason: String?) : SessionEvent
    }

    private val _state = MutableStateFlow<State>(State.Disconnected)
    val state: StateFlow<State> = _state

    private val _events = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<SessionEvent> = _events

    /**
     * 当前活跃租约（clientSessionId），每次 open 成功发新值（replay=1，新订阅者
     * 立即拿到现值）。timeline SSE 等 attach 门控流必须跟随它重订阅——租约轮换后
     * 旧租约的 live 帧会被服务端逐帧静默丢弃（sse.rs should_deliver_timeline_live）。
     */
    private val _leases = MutableSharedFlow<String>(
        replay = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val leases: SharedFlow<String> = _leases

    /**
     * 续租放弃信号（连续 2 次失败）：租约已死，主循环必须整体重连。旧实现由续租
     * 循环直接 connectOnce——与主循环自身的重开路径竞争（双租约，且主循环还挂在
     * 旧流的 collect 上直到它被服务端掐断）。改为发信号，由 streamEvents 监听后
     * 抛 LeaseExpired 交回 runWithBackoff，全进程只有一条重连路径。
     */
    private val _leaseExpired = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /**
     * 目标会话的现值（replay=1）。[setSessionId] 是异步的（要等锁、要掐旧主循环），
     * 只靠轮询 `currentSessionId()` 的订阅方会拖到下一条流自然断掉才换会话——
     * [TimelineStream] 用这个信号立刻改订阅。
     */
    private val _sessionSeed = MutableStateFlow(sessionId)
    val sessionSeed: StateFlow<String?> = _sessionSeed

    /** 主循环内部信号：当前流的租约已失效/轮换，按情况本地重订阅或整体重连。 */
    private object LeaseExpired : Exception("lease expired")

    private var instanceId: String = UUID.randomUUID().toString()
    private var clientSessionId: String? = null
    private var serverEpoch: String? = null
    private var cursor: String? = null

    private var mainLoop: Job? = null
    private var renewLoop: Job? = null
    private val mutex = Mutex()

    fun start() {
        check(mainLoop == null) { "already started" }
        mainLoop = scope.launch { runWithBackoff() }
    }

    /** 切换目标会话（发现新活跃会话/用户选择时调用）：重置 cursor 并重启主循环。 */
    fun setSessionId(newSessionId: String?) {
        scope.launch {
            mutex.withLock {
                if (sessionId == newSessionId) return@launch
                sessionId = newSessionId
                cursor = null
                _sessionSeed.value = newSessionId
            }
            mainLoop?.cancelAndJoin()
            mainLoop = scope.launch { runWithBackoff() }
        }
    }

    /** 当前目标会话（全局跟踪器/外部订阅者读取用；切会话后随主循环生效）。 */
    suspend fun currentSessionId(): String? = mutex.withLock { sessionId }

    suspend fun stop() {
        mainLoop?.cancelAndJoin()
        renewLoop?.cancel()
        renewLoop = null
        mainLoop = null
        mutex.withLock {
            clientSessionId = null
            serverEpoch = null
            cursor = null
            _state.value = State.Disconnected
        }
    }

    /** 提交 ToolPermissionRespond（tool 通道，免 driver seat）。 */
    suspend fun respondPermission(toolCallId: String, approved: Boolean, trustFolder: Boolean = false): CommandAck =
        postCommand(RingingProtocol.CHANNEL_TOOL, CommandEnvelope.toolPermissionRespond(toolCallId, approved, trustFolder))

    /**
     * 按交互类型应答：permission → tool_permission_respond；
     * plan → plan_review_respond；ask 的"拒绝"语义为关闭交互（中止挂起回合）。
     */
    suspend fun respondInteraction(
        kind: String,
        interactionId: String,
        callId: String?,
        approved: Boolean,
    ): CommandAck = when (kind) {
        "permission" -> respondPermission(callId ?: error("permission 交互缺少 call_id"), approved)
        "plan" -> postCommand(RingingProtocol.CHANNEL_CONTROL, CommandEnvelope.planReviewRespond(interactionId, approved))
        "ask" -> postCommand(RingingProtocol.CHANNEL_CONTROL, CommandEnvelope.interactionAskDismiss(interactionId))
        else -> error("未知交互类型 $kind")
    }

    /**
     * 发送用户消息（conversation 通道，driver 门控）。
     * 无人认领时直接放行；被拒（not_driver）则 claim 后重试——claim 是异步生效
     * （席位经 DriverChanged 落地），且他人租约未过期时 claim 会被拒（driver_busy），
     * 因此短退避重试数轮，仍失败则把占用状态作为最终错误返回。
     */
    suspend fun sendUserMessage(text: String): CommandAck =
        sendWithDriverRecovery(RingingProtocol.CHANNEL_CONVERSATION) {
            CommandEnvelope.conversationSendMessage(text)
        }

    /** 新建会话（control 通道）；not_driver 恢复策略同 [sendUserMessage]。 */
    suspend fun createSession(): CommandAck =
        sendWithDriverRecovery(RingingProtocol.CHANNEL_CONTROL) { CommandEnvelope.sessionCreate() }

    private suspend fun sendWithDriverRecovery(
        channel: String,
        envelope: () -> kotlinx.serialization.json.JsonObject,
    ): CommandAck {
        var ack = postCommand(channel, envelope())
        if (ack.status != "rejected" || ack.code != "not_driver") return ack
        // 最多 3 轮 claim+重试；每轮之间给 claim 落地/对方租约过期留时间。
        repeat(3) { attempt ->
            val claimResult = claimDriverSeat()
            when {
                // 席位已归我或已认领：稍等落地后重试命令
                claimResult.isSuccess -> delay(300L * (attempt + 1))
                // 他人租约仍活跃（driver_busy）：等其过期再试
                else -> delay(2_000L * (attempt + 1))
            }
            ack = postCommand(channel, envelope())
            if (ack.status != "rejected" || ack.code != "not_driver") return ack
        }
        return ack.copy(
            message = ack.message ?: "另一客户端正在驾驶该会话，稍后重试",
        )
    }

    /** 认领 driver 席位；返回 claim 是否已被接受（already_holder / claim_requested 均算）。 */
    private suspend fun claimDriverSeat(): Result<Unit> {
        val sid = mutex.withLock { clientSessionId } ?: return Result.failure(IllegalStateException("not connected"))
        val seed = mutex.withLock { sessionId } ?: return Result.failure(IllegalStateException("no session"))
        // mapCatching：Result.map 不捕获 transform 异常，require 抛出会漏到调用方
        return runCatching { api.driverClaim(sid, seed) }.mapCatching { resp ->
            require(resp.accepted) { "driver_busy: ${resp.reason ?: "unknown"}" } // accepted=false 即他人租约仍活跃
        }
    }

    private suspend fun postCommand(channel: String, command: kotlinx.serialization.json.JsonObject): CommandAck {
        val sid = mutex.withLock { clientSessionId } ?: error("not connected")
        val sessionId = mutex.withLock { sessionId }
        val commandId = UUID.randomUUID().toString()
        val ack = api.postCommand(
            sid,
            CommandEnvelope(
                channel = channel,
                commandId = commandId,
                clientInstanceId = instanceId,
                clientSessionId = sid,
                sessionId = sessionId,
                command = command,
            ),
        )
        // 对齐 Rust v2.rs：ack 对不上提交 id 说明回执错位（幂等重放/服务端异常），
        // 不能当成本命令的结果继续消费
        if (ack.commandId != commandId) {
            throw RingingApi.ApiException(
                httpStatus = 0,
                code = "ack_command_id_mismatch",
                message = "ack for ${ack.commandId} but sent $commandId",
            )
        }
        return ack
    }

    private suspend fun runWithBackoff() {
        var backoffMs = 1_000L
        while (scope.isActive) {
            _state.value = State.Connecting
            var transient = false
            try {
                connectOnce()
                streamEvents()
                // 只有走完一个完整周期（连接+流结束）才重置退避——否则 bootstrap 持续
                // 失败（如 409 snapshot_missing）会因 connectOnce 每次成功而重置成 1s 风暴
                backoffMs = 1_000L
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w(TAG, "connection loop failure: ${e.message}", e)
                val apiException = e as? RingingApi.ApiException
                // 会话尚未物化（session_not_found/snapshot_missing，404/409 或同义 code）是
                // 新建会话首个 canonical 事实落盘前的正常瞬态（对齐 Rust error.rs）：保持
                // 1s 短退避，不吃指数增长。正常路径下这类瞬态由 streamEvents 内部的
                // bootstrap 重试就地消化，不走到这里。
                transient = apiException?.isSessionNotReady == true
                // 只有"真鉴权失败"（无 code 的 unauthorized）才算吊销；lease_required 也是 401，
                // 但只是租约没 attach，属于常规竞态，绝不能触发清凭据
                _state.value = State.Failed(
                    e.message ?: e.javaClass.simpleName,
                    unauthorized = apiException?.isUnauthorized == true && apiException.code != "lease_required",
                )
            }
            delay(if (transient) 1_000L else backoffMs)
            if (!transient) backoffMs = min(backoffMs * 2, 30_000L)
        }
    }

    private suspend fun connectOnce() {
        val open = api.open(instanceId)
        mutex.withLock {
            clientSessionId = open.clientSessionId
            // daemon 重启（epoch 变化）后旧 canonical 游标语义失效：归零，streamEvents
            // 会重新 bootstrap 拿新快照（以前靠每轮重开时的 bootstrap 顺带覆盖，
            // 现在游标跨周期复用，必须在这里显式作废）
            if (serverEpoch != open.serverEpoch) cursor = null
            serverEpoch = open.serverEpoch
        }
        // attach 要在 Connected/bootstrap 前完成：timeline/content 等 attach 门控接口依赖它，
        // device 身份下 bootstrap 本身也要求先 attach（否则 401 lease_required）。失败不吞——
        // 带着未 attach 的租约只会撞 401/403，这里直接判本轮周期失败走退避重试。
        val knownSessionId = mutex.withLock { sessionId }
        if (knownSessionId != null) {
            val ack = postCommand(RingingProtocol.CHANNEL_CONTROL, CommandEnvelope.sessionAttach(knownSessionId))
            if (ack.status == "rejected") {
                throw RingingApi.ApiException(
                    httpStatus = 0,
                    code = ack.code,
                    message = "attach rejected: ${ack.message ?: ack.code ?: "unknown"}",
                )
            }
            android.util.Log.i(TAG, "attach ok lease=${open.clientSessionId.take(8)} session=${knownSessionId.take(12)} ack=${ack.status} ${ack.code ?: ""}")
        }
        mutex.withLock { _state.value = State.Connected(open.serverEpoch, open.clientSessionId) }
        _leases.tryEmit(open.clientSessionId)
        startRenewLoop(open.renewIntervalMs)
    }

    private fun startRenewLoop(renewIntervalMs: Long) {
        val sid = clientSessionId ?: return
        renewLoop?.cancel()
        renewLoop = scope.launch {
            delay(renewIntervalMs / 2) // 首个 tick 跳过，之后按半间隔续租
            var failures = 0
            while (isActive && sid == mutex.withLock { clientSessionId }) {
                try {
                    // 请求级 10s 超时（对齐 Rust session.rs BUG-2026-09-13-09）：
                    // daemon 冷启动挂起时 renew 不能永久阻塞续租循环
                    if (withTimeoutOrNull(10_000) { api.renew(sid) } == null) {
                        throw java.util.concurrent.TimeoutException("renew timed out")
                    }
                    failures = 0
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (++failures >= 2) {
                        // 租约已死。不发新租约、不直接重连（那会与主循环的重开路径竞争，
                        // 造成双租约且主循环仍挂在旧流的 collect 上）；归零游标（防陈旧
                        // epoch 游标跨租约复用）后发信号，streamEvents 感知后抛
                        // LeaseExpired 交回 runWithBackoff 走统一的重连周期。
                        mutex.withLock { cursor = null }
                        _leaseExpired.tryEmit(Unit)
                        break
                    }
                }
                delay(renewIntervalMs / 2)
            }
        }
    }

    private suspend fun streamEvents() {
        val sessionId = mutex.withLock { sessionId }
        if (sessionId == null) {
            // 无目标会话：租约照常保活（续租循环在跑），等续租放弃信号触发重连周期。
            // （旧实现 awaitCancellation 挂死——daemon 重启后 renew 失败也没人触发重连。）
            _leaseExpired.first()
            throw LeaseExpired
        }
        while (true) {
            val sid = mutex.withLock { clientSessionId } ?: throw LeaseExpired
            if (mutex.withLock { cursor } == null) {
                bootstrapSnapshot(sid, sessionId)
            }
            try {
                coroutineScope {
                    // 租约轮换/续租放弃：当前流作废——轮换走本地重订阅，放弃走整体重连
                    launch { _leases.drop(1).first(); throw RestartStream }
                    launch { _leaseExpired.first(); throw LeaseExpired }
                    val since = mutex.withLock { cursor }
                    api.events(sid, sessionId, since).collect { sse ->
                        when (sse) {
                            is RingingApi.SseEvent.Reset -> {
                                mutex.withLock { cursor = sse.reset.snapshotCursor }
                                throw RestartStream // 跳出 collect，本地重订阅
                            }

                            // 服务端丢弃积压（broadcast Lagged）主动掐流：游标仍有效，立即重订阅补尾巴
                            RingingApi.SseEvent.StreamTerminated -> {
                                android.util.Log.i(TAG, "stream terminated by server — resubscribing")
                                throw RestartStream
                            }

                            is RingingApi.SseEvent.Ringing -> handleEvent(sse.envelope)
                        }
                    }
                }
                // SSE 正常收尾（服务端 EOF）：游标仍有效，短暂停顿后原租约重订阅——
                // 不再像旧实现那样整轮重开（每次重连换 client_session_id 是重连风暴之源）
                android.util.Log.i(TAG, "events stream closed by server — resubscribing")
                delay(1_000)
            } catch (e: RestartStream) {
                // 预期内重订阅信号：立即继续
            }
        }
    }

    /**
     * 取 bootstrap 快照并投影。会话尚未物化（session_not_found/snapshot_missing，
     * 404/409）时**保持租约** 1s 短退避重试（对齐 Rust v2_stream 的 not-ready 短退避）：
     * 新建会话首个 canonical 事实落盘前就是这个状态，重开租约只会每秒轮换一次
     * client_session_id，旧租约上的全部 timeline SSE 随之 401 雪崩。
     * 时序不变：open → attach → bootstrap → SSE。
     */
    private suspend fun bootstrapSnapshot(sid: String, sessionId: String) {
        while (true) {
            try {
                val boot = api.bootstrap(sid, sessionId)
                mutex.withLock {
                    serverEpoch = boot.serverEpoch
                    cursor = boot.snapshotCursor
                }
                // bootstrap 快照自带当前活动状态，先投影一次（后续靠 SSE activity 增量更新）
                boot.activity?.let { _events.tryEmit(SessionEvent.Activity(it, null)) }
                // 快照里已挂起的交互也要投影（重连/后加入的客户端靠它看到积压请求）
                (boot.control?.state?.get("interactions") as? kotlinx.serialization.json.JsonArray)
                    ?.forEach { element ->
                        val o = element as? kotlinx.serialization.json.JsonObject ?: return@forEach
                        val kind = (o["kind"] as? JsonPrimitive)?.content ?: return@forEach
                        runCatching {
                            SessionEvent.InteractionRequested(
                                InteractionRequested(
                                    revision = 0,
                                    interactionId = (o["interaction_id"] as? JsonPrimitive)?.content ?: return@forEach,
                                    callId = (o["call_id"] as? JsonPrimitive)?.content,
                                    kind = kind,
                                    request = o["request"],
                                )
                            )
                        }.getOrNull()?.let { _events.tryEmit(it) }
                    }
                return
            } catch (e: RingingApi.ApiException) {
                if (!e.isSessionNotReady) throw e
                android.util.Log.i(TAG, "bootstrap ${e.httpStatus}/${e.code ?: "-"}: session not materialized — keep lease, retry in 1s")
                delay(1_000)
            }
        }
    }

    private object RestartStream : Exception("restart stream")

    private suspend fun handleEvent(envelope: EventEnvelope) {
        if (envelope.isReliable && envelope.cursor != null) {
            mutex.withLock { cursor = envelope.cursor }
        }
        if (envelope.payloadKind != "control_delta") return
        // payload = {"kind":"control_delta","data":{ kind, data:{...} }}
        val delta = envelope.payloadData ?: return
        val kind = (delta["kind"] as? JsonPrimitive)?.content ?: return
        val inner = (delta["data"] as? kotlinx.serialization.json.JsonObject)?.jsonObject ?: return
        val json = api.json
        when (kind) {
            "activity" -> runCatching { json.decodeFromJsonElement(ActivityDelta.serializer(), inner) }.getOrNull()?.let {
                _events.tryEmit(SessionEvent.Activity(it.state, it.turnId))
            }

            "interaction_requested" -> runCatching { json.decodeFromJsonElement(InteractionRequested.serializer(), inner) }.getOrNull()?.let {
                _events.tryEmit(SessionEvent.InteractionRequested(it))
            }

            "interaction_resolved" -> runCatching { json.decodeFromJsonElement(InteractionResolved.serializer(), inner) }.getOrNull()?.let {
                _events.tryEmit(SessionEvent.InteractionResolved(it, envelope.causationId))
            }

            "interaction_expired" -> runCatching { json.decodeFromJsonElement(InteractionExpired.serializer(), inner) }.getOrNull()?.let {
                _events.tryEmit(SessionEvent.InteractionExpired(it.interactionId, it.reason))
            }
        }
    }

    private companion object {
        const val TAG = "RingingConnection"
    }
}

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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
            }
            mainLoop?.cancelAndJoin()
            mainLoop = scope.launch { runWithBackoff() }
        }
    }

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
        return api.postCommand(
            sid,
            CommandEnvelope(
                channel = channel,
                commandId = UUID.randomUUID().toString(),
                clientInstanceId = instanceId,
                clientSessionId = sid,
                sessionId = sessionId,
                command = command,
            ),
        )
    }

    private suspend fun runWithBackoff() {
        var backoffMs = 1_000L
        while (scope.isActive) {
            _state.value = State.Connecting
            try {
                connectOnce()
                backoffMs = 1_000L
                streamEvents()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w(TAG, "connection loop failure: ${e.message}", e)
                _state.value = State.Failed(
                    e.message ?: e.javaClass.simpleName,
                    unauthorized = (e as? RingingApi.ApiException)?.isUnauthorized == true,
                )
            }
            delay(backoffMs)
            backoffMs = min(backoffMs * 2, 30_000L)
        }
    }

    private suspend fun connectOnce() {
        val open = api.open(instanceId)
        mutex.withLock {
            clientSessionId = open.clientSessionId
            serverEpoch = open.serverEpoch
        }
        // attach 要在 Connected 置位前完成：timeline/content 等 attach 门控接口依赖它
        val knownSessionId = mutex.withLock { sessionId }
        if (knownSessionId != null) {
            runCatching { postCommand(RingingProtocol.CHANNEL_CONTROL, CommandEnvelope.sessionAttach(knownSessionId)) }
                .onSuccess { android.util.Log.i(TAG, "attach ok lease=${open.clientSessionId.take(8)} session=${knownSessionId.take(12)} ack=${it.status} ${it.code ?: ""}") }
                .onFailure { android.util.Log.w(TAG, "attach FAILED: $it") }
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
                    api.renew(sid)
                    failures = 0
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (++failures >= 2) {
                        // 租约失效：重新 open（新 server_epoch 会使 SSE cursor 失效）
                        mutex.withLock { cursor = null }
                        connectOnce()
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
            awaitCancellation()
        }
        val sid = mutex.withLock { clientSessionId } ?: return
        if (mutex.withLock { cursor } == null) {
            val boot = api.bootstrap(sid, sessionId)
            mutex.withLock {
                if (serverEpoch != boot.serverEpoch) cursor = null
                serverEpoch = boot.serverEpoch
                cursor = boot.snapshotCursor
            }
            // attach 只读归属：/content 与后续 owns_session 校验的前提（M0 后成为硬要求）
            runCatching { postCommand(RingingProtocol.CHANNEL_CONTROL, CommandEnvelope.sessionAttach(sessionId)) }
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
        }
        val since = mutex.withLock { cursor }
        api.events(sid, sessionId, since).collect { sse ->
            when (sse) {
                is RingingApi.SseEvent.Reset -> {
                    mutex.withLock { cursor = sse.reset.snapshotCursor }
                    throw RestartStream // 跳出 collect，回到 connectOnce/streamEvents 循环
                }

                is RingingApi.SseEvent.Ringing -> handleEvent(sse.envelope)
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

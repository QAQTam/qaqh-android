package com.qaqh.harness.data.core

import com.qaqh.harness.data.core.SessionEvent as ContractEvent
import com.qaqh.harness.data.ringing.model.CommandAck
import com.qaqh.harness.data.ringing.model.InteractionKinds
import com.qaqh.harness.data.ringing.model.InteractionRequested
import com.qaqh.harness.data.ringing.model.InteractionResolved
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.min

/**
 * FFI 传输层之上的会话级编排(替代旧 RingingConnection 的对外契约):
 *
 * - 连接状态机 `State`(四态,Home/Settings/Service 零改动消费);
 * - 交互投影 `SessionEvent`(ConnectionService 的审批/活动通知源);
 * - bootstrap 快照流(聊天屏 reducer 的底座;Open/reset 后都会重发);
 * - 命令面(sendMessage/审批应答/driver 恢复)。
 *
 * 租约续租/重协商/单流退避重连在 Rust 内部完成;这里只负责——
 * 连接失败退避重试、目标会话切换(attach + activate_stream)、
 * 快照拉取时机(Open 状态 / resync 信号)。
 */
class CoreConnection(
    private val endpoint: String,
    private val token: String,
    private val tlsFingerprint: String?,
    private val scope: CoroutineScope,
) {
    sealed interface State {
        data object Disconnected : State
        data object Connecting : State
        data class Connected(
            val serverEpoch: String,
            val clientSessionId: String,
        ) : State

        data class Failed(
            val reason: String,
            /** 401(token 无效/设备被吊销):UI 应掉回配对屏,而不是无限重试。 */
            val unauthorized: Boolean = false,
        ) : State
    }

    /** 从会话流投影出的交互/活动事件(ConnectionService 消费)。 */
    sealed interface SessionEvent {
        data class Activity(val state: String, val turnId: String?) : SessionEvent

        data class InteractionRequested(val requested: com.qaqh.harness.data.ringing.model.InteractionRequested) : SessionEvent

        data class InteractionResolved(
            val resolved: com.qaqh.harness.data.ringing.model.InteractionResolved,
            val causationCommandId: String?,
        ) : SessionEvent

        data class InteractionExpired(val interactionId: String, val reason: String?) : SessionEvent

        data class DriverChanged(val holder: String?, val epoch: Long) : SessionEvent

        data class SessionTitle(val title: String?) : SessionEvent
    }

    /** bootstrap 快照 + 触发原因。 */
    data class Snapshot(val sessionId: String, val bootstrap: SessionBootstrap, val seq: Long)

    private val _state = MutableStateFlow<State>(State.Disconnected)
    val state: StateFlow<State> = _state

    private val _events = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<SessionEvent> = _events

    private val _snapshots = MutableSharedFlow<Snapshot>(extraBufferCapacity = 8, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)
    val snapshots: SharedFlow<Snapshot> = _snapshots

    /** 当前会话的最新快照(迟到订阅者直接取现值,不用等下一次 Open)。 */
    private val _latestSnapshot = MutableStateFlow<Snapshot?>(null)
    val latestSnapshot: StateFlow<Snapshot?> = _latestSnapshot.asStateFlow()

    /** 当前活跃租约(replay=1;保留旧契约——切会话/重连感知用)。 */
    private val _leases = MutableSharedFlow<String>(
        replay = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val leases: SharedFlow<String> = _leases

    private val _sessionSeed = MutableStateFlow<String?>(null)
    val sessionSeed: StateFlow<String?> = _sessionSeed.asStateFlow()

    val client = CoreClient(scope)

    private var mainLoop: Job? = null
    private val mutex = Mutex()

    fun start() {
        check(mainLoop == null) { "already started" }
        mainLoop = scope.launch { runWithBackoff() }
    }

    /** 切换目标会话:attach + 起单流(快照由流 Open 状态触发拉取)。 */
    fun setSessionId(newSessionId: String?) {
        scope.launch {
            mutex.withLock {
                if (_sessionSeed.value == newSessionId) return@launch
                _sessionSeed.value = newSessionId
            }
        }
    }

    suspend fun currentSessionId(): String? = _sessionSeed.value

    suspend fun stop() {
        mainLoop?.cancel()
        mainLoop = null
        client.shutdown()
        mutex.withLock { _state.value = State.Disconnected }
    }

    // ── 命令面 ──

    /**
     * 发送用户消息(driver 门控):无人认领放行;not_driver 则 claim 后重试——
     * claim 异步生效(席位经 DriverChanged 落地),他人租约未过期时被拒
     * (driver_busy),短退避重试数轮。
     */
    suspend fun sendUserMessage(text: String): CommandAck =
        sendWithDriverRecovery { seed -> client.sendMessage(seed, text) }

    /** 新建会话(Global 命令);not_driver 恢复策略同 sendUserMessage。 */
    suspend fun createSession(): CommandAck =
        sendWithDriverRecovery { _ -> client.createSession() }

    /** 按交互类型应答(admin/Direct 身份的 canonical 命令通道)。 */
    suspend fun respondInteraction(
        kind: String,
        interactionId: String,
        callId: String?,
        approved: Boolean,
    ): CommandAck {
        val seed = _sessionSeed.value ?: error("no session")
        return when (kind) {
            InteractionKinds.PERMISSION -> client.respondToolPermission(
                seed,
                callId ?: error("permission 交互缺少 call_id"),
                approved,
            )
            InteractionKinds.PLAN -> client.respondPlanReview(seed, interactionId, approved)
            // ask 的"拒绝"语义 = 关闭交互(中止挂起回合)
            InteractionKinds.ASK -> client.dismissAsk(seed, interactionId)
            else -> error("未知交互类型 $kind")
        }
    }

    private suspend fun sendWithDriverRecovery(command: suspend (seed: String) -> CommandAck): CommandAck {
        val seed = _sessionSeed.value ?: error("no session")
        var ack = command(seed)
        if (ack.status != "rejected" || ack.code != "not_driver") return ack
        repeat(3) { attempt ->
            val claimResult = runCatching { client.driverClaim(seed, null) }
            when {
                claimResult.isSuccess -> delay(300L * (attempt + 1))
                else -> delay(2_000L * (attempt + 1))
            }
            ack = command(seed)
            if (ack.status != "rejected" || ack.code != "not_driver") return ack
        }
        return ack.copy(message = ack.message ?: "另一客户端正在驾驶该会话,稍后重试")
    }

    // ── 连接主循环 ──

    private suspend fun runWithBackoff() {
        var backoffMs = 1_000L
        while (scope.isActive) {
            _state.value = State.Connecting
            try {
                client.connect(endpoint, token, tlsFingerprint)
                val lease = client.leaseState()
                _state.value = State.Connected(
                    serverEpoch = lease?.serverEpoch ?: "",
                    clientSessionId = lease?.clientSessionId ?: "",
                )
                lease?.clientSessionId?.let { _leases.tryEmit(it) }
                backoffMs = 1_000L
                watchConnection()
                // watchConnection 正常返回 = shutdown;循环条件兜底
                return
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w(TAG, "connect failed: ${e.message}")
                val transport = e as? CoreTransportException
                _state.value = State.Failed(
                    e.message ?: e.javaClass.simpleName,
                    unauthorized = transport?.isUnauthorized == true &&
                        transport.code != "lease_required",
                )
            }
            delay(backoffMs)
            backoffMs = min(backoffMs * 2, 30_000L)
        }
    }

    /**
     * 连接后的常驻编排:目标会话切换 → attach+流;流 Open/reset → bootstrap →
     * 投影快照与交互事件。直到 stop。
     */
    private suspend fun watchConnection() {
        var activeSeed: String? = null
        val jobs = mutableListOf<Job>()
        try {
            // 事件投影泵:交互/活动/标题 → SessionEvent
            jobs += scope.launch {
                client.frames.collect { frame ->
                    when (val e = frame.event) {
                        is ContractEvent.ActivityChanged ->
                            _events.tryEmit(SessionEvent.Activity(e.activity, e.currentTurnId))
                        is ContractEvent.InteractionRequested -> {
                            val v = e.interaction
                            _events.tryEmit(
                                SessionEvent.InteractionRequested(
                                    InteractionRequested(
                                        interactionId = v.interactionId,
                                        callId = v.callId,
                                        kind = v.kind,
                                        request = v.request,
                                        expiresAtMs = v.expiresAtMs,
                                    ),
                                ),
                            )
                        }
                        is ContractEvent.InteractionResolved ->
                            _events.tryEmit(
                                SessionEvent.InteractionResolved(
                                    InteractionResolved(
                                        interactionId = e.interactionId,
                                        decision = e.decision,
                                    ),
                                    causationCommandId = null,
                                ),
                            )
                        is ContractEvent.SessionMetaChanged ->
                            _events.tryEmit(SessionEvent.SessionTitle(e.title))
                        is ContractEvent.DriverChanged ->
                            _events.tryEmit(SessionEvent.DriverChanged(e.driver?.holder, e.driver?.epoch ?: 0))
                        else -> Unit
                    }
                }
            }
            // 快照泵:Open 状态与 resync 都触发 bootstrap;bootstrap 后投影快照内积压交互
            jobs += scope.launch {
                client.statuses.collect { st ->
                    if (st.sessionId == activeSeed && st.isOpen) refreshSnapshot(st.sessionId)
                }
            }
            jobs += scope.launch {
                client.resets.collect { r -> refreshSnapshot(r.sessionId) }
            }
            // 会话切换泵:seed 变化 → deactivate 旧流,attach + activate 新流
            while (scope.isActive) {
                val wanted = _sessionSeed.first { it != activeSeed }
                activeSeed?.let { seed -> runCatching { client.deactivateStream(seed) } }
                activeSeed = wanted
                if (wanted != null) {
                    runCatching { client.attach(wanted) }
                        .onFailure { android.util.Log.w(TAG, "attach $wanted failed: ${it.message}") }
                    runCatching { client.activateStream(wanted) }
                        .onFailure { android.util.Log.w(TAG, "activate $wanted failed: ${it.message}") }
                }
            }
        } finally {
            jobs.forEach { it.cancel() }
        }
    }

    /** bootstrap → 快照投影(交互积压 + seq 水位);session 未物化保持 1s 短退避。 */
    private suspend fun refreshSnapshot(sessionId: String) {
        while (scope.isActive) {
            val boot = try {
                client.bootstrap(sessionId)
            } catch (e: CoreTransportException) {
                if (e.code == "session_not_found" || e.httpStatus == 404 || e.httpStatus == 409) {
                    delay(1_000)
                    continue
                }
                android.util.Log.w(TAG, "bootstrap failed: ${e.message}")
                return
            } catch (e: Exception) {
                android.util.Log.w(TAG, "bootstrap failed: ${e.message}")
                return
            }
            _snapshots.tryEmit(Snapshot(sessionId, boot, boot.state.seq))
            _latestSnapshot.value = Snapshot(sessionId, boot, boot.state.seq)
            // 快照内已挂起的交互也要投影(重连/后加入的客户端看到积压请求)
            boot.state.interactions.forEach { v ->
                _events.tryEmit(
                    SessionEvent.InteractionRequested(
                        InteractionRequested(
                            interactionId = v.interactionId,
                            callId = v.callId,
                            kind = v.kind,
                            request = v.request,
                            expiresAtMs = v.expiresAtMs,
                        ),
                    ),
                )
            }
            // Connected 的权威字段(epoch)随快照补齐
            val st = _state.value
            if (st is State.Connected && st.serverEpoch.isEmpty()) {
                _state.value = st.copy(serverEpoch = boot.serverEpoch)
            }
            return
        }
    }

    private companion object {
        const val TAG = "CoreConnection"
    }
}

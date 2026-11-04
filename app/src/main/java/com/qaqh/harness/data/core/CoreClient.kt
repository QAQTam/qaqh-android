package com.qaqh.harness.data.core

import com.qaqh.harness.core.ffi.CoreException
import com.qaqh.harness.core.ffi.CoreHandlers
import com.qaqh.harness.core.ffi.LeaseState
import com.qaqh.harness.core.ffi.QaqhClient
import com.qaqh.harness.core.ffi.StreamStatus
import com.qaqh.harness.core.ffi.connectClient
import com.qaqh.harness.data.ringing.model.CommandAck
import com.qaqh.harness.data.ringing.model.DeviceChallengesResponse
import com.qaqh.harness.data.ringing.model.SessionListEntry
import com.qaqh.harness.data.ringing.model.WorkspaceEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

/** 单流事件帧(session_id + seq + 已解析事件)。 */
data class CoreFrame(val sessionId: String, val seq: Long, val event: SessionEvent)

/** 服务端 resync_required:订阅方应重新 bootstrap。 */
data class CoreReset(val sessionId: String, val reason: String)

/** 单流状态迁移(直映射 Rust V2StreamStatus)。 */
data class CoreStatus(val sessionId: String, val status: StreamStatus) {
    val isOpen: Boolean get() = status is StreamStatus.Open
}

/** FFI 传输异常(保留 uniffi 异常的可判别信息)。 */
class CoreTransportException(val error: CoreException) :
    Exception(error.message ?: error::class.simpleName) {
    /** Api{code} 判别(lease_required/session_not_found/insufficient_scope/…)。 */
    val code: String? get() = (error as? CoreException.Api)?.code
    val httpStatus: Int? get() = when (error) {
        is CoreException.Http -> error.status.toInt()
        is CoreException.Api -> error.status.toInt()
        else -> null
    }
    val isUnauthorized: Boolean get() = httpStatus == 401
}

/**
 * Rust core(uniffi)的 Kotlin 包装:传输知识全部在 .so 里(lease 续租/重协商、
 * 退避重连、cursor、SSE 解码、TLS pin),本类只做三件事——
 * 1. 回调(JNA 线程)→ Flow 的桥(UNLIMITED channel 解耦,单泵转投保序);
 * 2. JSON 字符串 → 契约模型的解析边界;
 * 3. 异常折叠(CoreException → CoreTransportException,保 code/status 判别)。
 */
class CoreClient(private val scope: CoroutineScope) : CoreHandlers {

    private var client: QaqhClient? = null

    private val connected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = connected.asStateFlow()

    private val rawFrames = Channel<CoreFrame>(Channel.UNLIMITED)
    private val _frames = MutableSharedFlow<CoreFrame>(
        extraBufferCapacity = 1024,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.SUSPEND,
    )
    /** 单流事件(已解析);回调线程经 channel 转投,顺序保持。 */
    val frames: SharedFlow<CoreFrame> = _frames

    private val _statuses = MutableSharedFlow<CoreStatus>(extraBufferCapacity = 64)
    val statuses: SharedFlow<CoreStatus> = _statuses

    private val _resets = MutableSharedFlow<CoreReset>(extraBufferCapacity = 16)
    val resets: SharedFlow<CoreReset> = _resets

    // ── CoreHandlers(JNA 回调线程,只做 trySend/tryEmit,绝不阻塞)──

    override fun onEvent(sessionId: String, seq: ULong, eventJson: String) {
        val event = parseSessionEvent(eventJson) ?: return // 未知新变体:跳过
        rawFrames.trySend(CoreFrame(sessionId, seq.toLong(), event))
    }

    override fun onReset(sessionId: String, reason: String) {
        _resets.tryEmit(CoreReset(sessionId, reason))
    }

    override fun onStatus(sessionId: String, status: StreamStatus) {
        _statuses.tryEmit(CoreStatus(sessionId, status))
    }

    override fun onLiveness() {
        // 字节级存活信号;当前无消费方(旧实现同样只用于诊断)
    }

    // ── 生命周期 ──

    /** 连接 daemon(remote 直连):open 租约 + 起续租/attach 重放后台任务。 */
    suspend fun connect(baseUrl: String, deviceToken: String, tlsFingerprint: String?) {
        check(client == null) { "core client already connected" }
        val c = guardedSuspend {
            connectClient(
                baseUrl = baseUrl,
                deviceToken = deviceToken,
                tlsFp = tlsFingerprint,
                handlers = this,
            )
        }
        client = c
        connected.value = true
        // channel → SharedFlow 单泵:保持帧序,回调线程永不挂起
        scope.launch {
            for (frame in rawFrames) _frames.emit(frame)
        }
    }

    /** 停全部后台任务(续租+流)。幂等;对象销毁另有 uniffi 侧 Drop 兜底。 */
    fun shutdown() {
        client?.shutdown()
        client = null
        connected.value = false
    }

    // ── 流编排 ──

    /** attach 一个 seed(SessionResume;租约重协商后 Rust 侧自动重放)。 */
    suspend fun attach(sessionId: String): CommandAck = decode { c().attach(sessionId) }

    suspend fun activateStream(sessionId: String) = guardedSuspend { c().activateStream(sessionId) }

    suspend fun deactivateStream(sessionId: String) =
        guardedSuspend { c().deactivateStream(sessionId) }

    suspend fun bootstrap(sessionId: String): SessionBootstrap = decode { c().bootstrapJson(sessionId) }

    /** 当前租约协商结果(Connected 状态字段来源);未 open 返回 null。 */
    suspend fun leaseState(): LeaseState? = guardedSuspend { c().leaseState() }

    // ── 查询 ──

    suspend fun sessionList(): List<SessionListEntry> = decode { c().sessionListJson() }

    suspend fun workspaceList(): List<WorkspaceEntry> = decode { c().workspaceListJson() }

    suspend fun sessionActivity(): JsonElement = decode { c().sessionActivityJson() }

    suspend fun todoStatus(sessionId: String): JsonElement = decode { c().todoStatusJson(sessionId) }

    // ── 命令(typed 糖;ack 统一解析)──

    suspend fun respondToolPermission(sessionId: String, toolCallId: String, approved: Boolean): CommandAck =
        decode { c().respondToolPermission(sessionId, toolCallId, approved) }

    suspend fun respondAsk(sessionId: String, interactionId: String, answersJson: String): CommandAck =
        decode { c().respondAsk(sessionId, interactionId, answersJson) }

    suspend fun respondPlanReview(
        sessionId: String,
        interactionId: String,
        approved: Boolean,
        message: String? = null,
        autonomous: Boolean = false,
    ): CommandAck =
        decode { c().respondPlanReview(sessionId, interactionId, approved, message, autonomous) }

    suspend fun dismissAsk(sessionId: String, interactionId: String): CommandAck = decode {
        c().sendCommandJson(
            sessionId,
            """{"type":"interaction.ask.dismiss","interaction_id":"$interactionId"}""",
        )
    }

    suspend fun sendMessage(sessionId: String, text: String): CommandAck =
        decode { c().sendMessage(sessionId, text) }

    suspend fun createSession(): CommandAck =
        decode { c().sendCommandJson(null, """{"type":"session.create"}""") }

    suspend fun cancelTurn(sessionId: String, turnId: String?): CommandAck =
        decode { c().cancelTurn(sessionId, turnId) }

    suspend fun driverClaim(sessionId: String, staleHolder: String?): CommandAck =
        decode { c().driverClaim(sessionId, staleHolder) }

    suspend fun driverRelease(sessionId: String): CommandAck =
        decode { c().driverRelease(sessionId) }

    // ── device 审批面 ──

    suspend fun deviceChallenges(sessionId: String): List<com.qaqh.harness.data.ringing.model.ApprovalChallenge> =
        decode<DeviceChallengesResponse> { c().deviceChallengesJson(sessionId) }.challenges

    suspend fun respondDeviceApproval(
        sessionId: String,
        challengeId: String,
        decision: String,
        payloadJson: String? = null,
    ): CommandAck = decode {
        c().respondDeviceApproval(sessionId, challengeId, decision, payloadJson)
    }

    // ── content ──

    /** 下载 content(sha256 校验在 Rust 侧)→ UTF-8 文本(审批摘要用)。 */
    suspend fun contentText(contentRef: String): String = guardedSuspend {
        val bytes = c().downloadContent(contentRef)
        String(bytes, Charsets.UTF_8)
    }

    // ── 内部 ──

    private fun c(): QaqhClient = client ?: throw CoreException.Transport(
        detail = "core client not connected",
    )

    private suspend inline fun <T> guardedSuspend(block: () -> T): T = try {
        block()
    } catch (e: CoreException) {
        throw CoreTransportException(e)
    }

    private suspend inline fun <reified T> decode(crossinline block: suspend () -> String): T =
        guardedSuspend {
            val raw = block()
            runCatching { CoreJson.decodeFromString<T>(raw) }.getOrElse { error ->
                throw CoreTransportException(
                    CoreException.Protocol(detail = "decode: $error"),
                )
            }
        }
}

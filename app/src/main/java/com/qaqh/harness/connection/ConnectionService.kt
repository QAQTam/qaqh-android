package com.qaqh.harness.connection

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.qaqh.harness.MainActivity
import com.qaqh.harness.data.pairing.CredentialStore
import com.qaqh.harness.data.ringing.RingingConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** 当前待审批（v2 interaction_requested, kind=permission）投影。 */
data class PendingApproval(
    val interactionId: String,
    /** permission | ask | plan */
    val kind: String,
    val callId: String?,
    val summary: String,
    val expiresAtMs: Long?,
)

/**
 * 常驻前台服务：持有 [RingingConnection]，把 SSE 事件投影为
 * 连接状态 / 会话活动 / 待审批三张全局 StateFlow，UI 层直接收集。
 *
 * 会话发现采用建议稿 §6 的"单活跃流 + 轮询"模式：每 15s 拉 `session.list`，
 * 取 running 会话（否则最近更新的一个），session_id 变化时切换事件流。
 * 审批请求到达时发高优先级通知（点进 MainActivity）。
 */
class ConnectionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var connection: RingingConnection? = null
    private val countdownJobs = HashMap<String, kotlinx.coroutines.Job>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, statusNotification("正在连接电脑端…"))
        val creds = CredentialStore(this).load()
        if (creds == null) {
            stopSelf()
            return
        }
        val conn = RingingConnection(
            endpoint = creds.baseUrl,
            token = creds.token,
            sessionId = null, // 先建租约，发现会话后再 setSessionId
            scope = scope,
        )
        connection = conn
        Companion.connection = conn
        scope.launch { conn.events.collect { handleEvent(it) } } // handleEvent 为 suspend，逐事件串行处理
        scope.launch {
            conn.state.collect { st ->
                _connectionState.value = st
                notifyStatus(
                    when (st) {
                        is RingingConnection.State.Connected -> "已连接电脑端"
                        is RingingConnection.State.Connecting -> "正在连接电脑端…"
                        is RingingConnection.State.Failed -> "连接失败，将自动重试"
                        RingingConnection.State.Disconnected -> "已断开"
                    }
                )
            }
        }
        scope.launch { sessionDiscoveryLoop(conn) }
        conn.start()
    }

    override fun onDestroy() {
        scope.cancel()
        connection = null
        Companion.connection = null
        _connectionState.value = RingingConnection.State.Disconnected
        _activity.value = null
        _pendingApprovals.value = emptyList()
        super.onDestroy()
    }

    /** 每 15s 发现一次活跃会话；session_id 变化时切换事件流。 */
    private suspend fun sessionDiscoveryLoop(conn: RingingConnection) {
        var currentSeed: String? = null
        while (scope.isActive) {
            delay(15_000)
            val sid = (_connectionState.value as? RingingConnection.State.Connected)?.clientSessionId ?: continue
            val sessions = runCatching { withTimeoutOrNull(10_000) { conn.api.sessionList(sid) } }.getOrNull()
                ?: continue
            if (sessions.isEmpty()) continue
            val target = sessions.firstOrNull { it.running }
                ?: sessions.maxByOrNull { it.updatedAt }
                ?: continue
            if (target.sessionId != currentSeed) {
                currentSeed = target.sessionId
                _sessionInfo.value = target
                conn.setSessionId(target.sessionId)
            }
        }
    }

    private suspend fun handleEvent(event: RingingConnection.SessionEvent) {
        when (event) {
            is RingingConnection.SessionEvent.Activity -> {
                _activity.value = event.state
                notifyStatus(
                    when (event.state) {
                        "running" -> "会话正在工作…"
                        "idle" -> "会话空闲"
                        "interrupted" -> "会话已中断"
                        else -> "会话活动：${event.state}"
                    }
                )
            }

            is RingingConnection.SessionEvent.InteractionRequested -> {
                val r = event.requested
                val approval = PendingApproval(
                    interactionId = r.interactionId,
                    kind = r.kind,
                    callId = r.callId,
                    summary = extractSummary(r),
                    expiresAtMs = r.expiresAtMs,
                )
                _pendingApprovals.value = _pendingApprovals.value + approval
                notifyApproval(approval)
            }

            is RingingConnection.SessionEvent.InteractionResolved -> {
                cancelApprovalCountdown(event.resolved.interactionId)
                _pendingApprovals.value =
                    _pendingApprovals.value.filterNot { it.interactionId == event.resolved.interactionId }
            }

            is RingingConnection.SessionEvent.InteractionExpired -> {
                cancelApprovalCountdown(event.interactionId)
                _pendingApprovals.value =
                    _pendingApprovals.value.filterNot { it.interactionId == event.interactionId }
            }
        }
    }

    private suspend fun extractSummary(r: com.qaqh.harness.data.ringing.model.InteractionRequested): String {
        // request 是 ContentValue {kind:"inline",data:{text:...}} 或 {kind:"ref",data:{content_ref:"sha256:..."}}
        val data = (r.request as? kotlinx.serialization.json.JsonObject)?.get("data")
            as? kotlinx.serialization.json.JsonObject
        (data?.get("text") as? kotlinx.serialization.json.JsonPrimitive)?.content?.let { return it }
        // ref 型：拉原文（需已 attach）；原文可能是 {"text":...} JSON 或纯文本
        val contentRef = (data?.get("content_ref") as? kotlinx.serialization.json.JsonPrimitive)?.content
        if (contentRef != null) {
            val sid = (_connectionState.value as? RingingConnection.State.Connected)?.clientSessionId
            if (sid != null) {
                val raw = runCatching { withTimeoutOrNull(5_000) { connection?.api?.content(sid, contentRef) } }.getOrNull()
                if (!raw.isNullOrBlank()) {
                    val parsed = runCatching {
                        (connection?.api?.json?.parseToJsonElement(raw) as? kotlinx.serialization.json.JsonObject)
                            ?.get("text") as? kotlinx.serialization.json.JsonPrimitive
                    }.getOrNull()?.content
                    return parsed ?: raw.take(300)
                }
            }
        }
        return r.callId ?: "工具调用请求"
    }

    // ---------- 通知 ----------

    private fun notifyStatus(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, statusNotification(text))
    }

    private fun statusNotification(text: String): Notification {
        ensureChannels()
        return NotificationCompat.Builder(this, CHANNEL_STATUS)
            .setContentTitle("QAQ-Harness")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
    }

    private fun notifyApproval(approval: PendingApproval) {
        ensureChannels()
        if (android.os.Build.VERSION.SDK_INT >= 36 && approval.expiresAtMs != null) {
            startApprovalCountdown(approval)
        } else {
            postApprovalNotification(approval, liveUpdatePct = null)
        }
    }

    /** Android 16 Live Update：带剩余时间进度条的审批通知，每秒刷新直到 resolved/expired。 */
    private fun startApprovalCountdown(approval: PendingApproval) {
        countdownJobs.remove(approval.interactionId)?.cancel()
        countdownJobs[approval.interactionId] = scope.launch {
            val total = (approval.expiresAtMs!! - System.currentTimeMillis()).coerceAtLeast(1L)
            while (isActive) {
                val remaining = (approval.expiresAtMs!! - System.currentTimeMillis()).coerceAtLeast(0L)
                postApprovalNotification(approval, liveUpdatePct = ((remaining * 100) / total).toInt())
                if (remaining <= 0L) break
                delay(1_000)
            }
        }
    }

    private fun postApprovalNotification(approval: PendingApproval, liveUpdatePct: Int?) {
        val deepLink = PendingIntent.getActivity(
            this,
            approval.interactionId.hashCode(),
            Intent(this, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = if (liveUpdatePct != null && android.os.Build.VERSION.SDK_INT >= 36) {
            // Notification.ProgressStyle（API 36+，本机 android-36 android.jar 已核对 API 面）
            val style = android.app.Notification.ProgressStyle()
                .addProgressSegment(
                    android.app.Notification.ProgressStyle.Segment(100).setColor(0xFFB3261E.toInt())
                )
                .setProgress(liveUpdatePct.coerceIn(0, 100))
            Notification.Builder(this, CHANNEL_APPROVALS)
                .setContentTitle("待审批请求")
                .setContentText(approval.summary)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setCategory(Notification.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setContentIntent(deepLink)
                .setStyle(style)
                .build()
        } else {
            NotificationCompat.Builder(this, CHANNEL_APPROVALS)
                .setContentTitle("待审批请求")
                .setContentText(approval.summary)
                .setStyle(NotificationCompat.BigTextStyle().bigText(approval.summary))
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setContentIntent(deepLink)
                .build()
        }
        getSystemService(NotificationManager::class.java)
            .notify(APPROVAL_NOTIFICATION_BASE + approval.interactionId.hashCode(), notification)
    }

    private fun cancelApprovalCountdown(interactionId: String) {
        countdownJobs.remove(interactionId)?.cancel()
    }

    private fun ensureChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, "连接状态", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_APPROVALS, "审批请求", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    companion object {
        private const val CHANNEL_STATUS = "connection"
        private const val CHANNEL_APPROVALS = "approvals"
        private const val NOTIFICATION_ID = 1
        private const val APPROVAL_NOTIFICATION_BASE = 1000

        private val _connectionState = MutableStateFlow<RingingConnection.State>(RingingConnection.State.Disconnected)
        val connectionState: StateFlow<RingingConnection.State> = _connectionState.asStateFlow()

        private val _activity = MutableStateFlow<String?>(null)
        val activity: StateFlow<String?> = _activity.asStateFlow()

        private val _pendingApprovals = MutableStateFlow<List<PendingApproval>>(emptyList())
        val pendingApprovals: StateFlow<List<PendingApproval>> = _pendingApprovals.asStateFlow()

        private val _sessionInfo = MutableStateFlow<com.qaqh.harness.data.ringing.model.SessionListEntry?>(null)
        val sessionInfo: StateFlow<com.qaqh.harness.data.ringing.model.SessionListEntry?> = _sessionInfo.asStateFlow()

        @Volatile
        private var connection: RingingConnection? = null

        private val _lastRespondError = MutableStateFlow<String?>(null)
        val lastRespondError: StateFlow<String?> = _lastRespondError.asStateFlow()

        /** 审批应答：按 kind 分派；当前阶段仍用 canonical id（后端 M0 后换 opaque challenge id）。 */
        suspend fun respondApproval(approval: PendingApproval, approved: Boolean): Result<com.qaqh.harness.data.ringing.model.CommandAck> {
            val conn = connection ?: return Result.failure(IllegalStateException("未连接"))
            val result = runCatching { conn.respondInteraction(approval.kind, approval.interactionId, approval.callId, approved) }
            result.onSuccess { ack ->
                if (ack.status != "accepted") {
                    _lastRespondError.value = "命令被拒绝：${ack.code ?: ""} ${ack.message ?: ""}"
                }
            }.onFailure { e ->
                _lastRespondError.value = "应答失败：${e.message}"
            }
            return result
        }

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ConnectionService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ConnectionService::class.java))
        }
    }
}

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
import com.qaqh.harness.data.pairing.pinnedHttpClient
import com.qaqh.harness.data.ringing.RingingConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
            client = pinnedHttpClient(creds.tlsFingerprint), // https 自签：SPKI 指纹即信任锚
        )
        connection = conn
        Companion.connection = conn
        scope.launch { conn.events.collect { handleEvent(it) } } // handleEvent 为 suspend，逐事件串行处理
        scope.launch {
            var unauthorizedFailures = 0
            conn.state.collect { st ->
                if (st is RingingConnection.State.Failed && st.unauthorized) {
                    // 连续 2 次真鉴权失败才认定吊销（单次 401 可能是竞态误报，wipe 不可逆）
                    unauthorizedFailures++
                    if (unauthorizedFailures < 2) {
                        notifyStatus("认证异常，重试中…")
                        return@collect
                    }
                    android.util.Log.w("ConnectionService", "repeated 401 from daemon — device token invalid/revoked, unpairing")
                    CredentialStore(this@ConnectionService).clear()
                    _authRevoked.value = true
                    stopSelf()
                    return@collect
                }
                unauthorizedFailures = 0
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
        var currentSessionId: String? = null
        while (scope.isActive) {
            // 先等连接就绪、立刻首拉，再进周期：冷启动列表 ~1-2s 可见
            //（原实现先 delay(15s)，Home 的 refreshNow 又因未连接空手而归，列表空等 15s）
            conn.state.first { it is RingingConnection.State.Connected }
            val sessions = refreshSessions(conn)
            if (sessions.isNotEmpty()) {
                val target = sessions.firstOrNull { it.running }
                    ?: sessions.maxByOrNull { it.updatedAt }
                if (target != null && target.sessionId != currentSessionId) {
                    currentSessionId = target.sessionId
                    _sessionInfo.value = target
                    conn.setSessionId(target.sessionId)
                }
            }
            delay(15_000)
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

    private val summaryJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    /** ask 载荷（{questions:[{question,options}...]}，可能作为 JSON 字符串嵌在 text 里）→ 摘要。 */
    private fun askSummaryOf(element: kotlinx.serialization.json.JsonElement?): String? {
        val obj = element as? kotlinx.serialization.json.JsonObject ?: return null
        val questions = obj["questions"] as? kotlinx.serialization.json.JsonArray ?: return null
        val texts = questions.mapNotNull { q ->
            ((q as? kotlinx.serialization.json.JsonObject)?.get("question") as? kotlinx.serialization.json.JsonPrimitive)
                ?.content?.trim()?.takeIf { it.isNotEmpty() }
        }
        if (texts.isEmpty()) return null
        return if (texts.size == 1) texts[0] else "共 ${texts.size} 问：${texts[0]}"
    }

    private suspend fun extractSummary(r: com.qaqh.harness.data.ringing.model.InteractionRequested): String {
        // request 是 ContentValue {kind:"inline",data:{text:...}} 或 {kind:"ref",data:{content_ref:"sha256:..."}}
        val data = (r.request as? kotlinx.serialization.json.JsonObject)?.get("data")
            as? kotlinx.serialization.json.JsonObject
        android.util.Log.i("ConnectionService", "extractSummary kind=${r.kind} request=${r.request.toString().take(160)}")
        (data?.get("text") as? kotlinx.serialization.json.JsonPrimitive)?.content?.let { text ->
            // ask 载荷常以 JSON 字符串嵌在 text 里：能解析出 questions 就取问题文本，不透传原始 JSON
            runCatching { summaryJson.parseToJsonElement(text) }.getOrNull()?.let { parsed ->
                askSummaryOf(parsed)?.let { return it }
            }
            return text
        }
        askSummaryOf(data)?.let { return it }
        // ref 型：拉原文（需已 attach）；原文可能是 ask 载荷 JSON、{"text":...} 或纯文本
        val contentRef = (data?.get("content_ref") as? kotlinx.serialization.json.JsonPrimitive)?.content
        if (contentRef != null) {
            val sid = (_connectionState.value as? RingingConnection.State.Connected)?.clientSessionId
            if (sid != null) {
                val raw = runCatching { withTimeoutOrNull(5_000) { connection?.api?.content(sid, contentRef) } }.getOrNull()
                if (!raw.isNullOrBlank()) {
                    val parsed = runCatching { connection?.api?.json?.parseToJsonElement(raw) }.getOrNull()
                    askSummaryOf(parsed)?.let { return it }
                    val parsedText = (parsed as? kotlinx.serialization.json.JsonObject)
                        ?.get("text") as? kotlinx.serialization.json.JsonPrimitive
                    return parsedText?.content ?: raw.take(300)
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
        private const val CHANNEL_TODO = "todo"
        private const val NOTIFICATION_ID = 1
        private const val APPROVAL_NOTIFICATION_BASE = 1000
        private const val TODO_NOTIFICATION_ID = 2

        private val _connectionState = MutableStateFlow<RingingConnection.State>(RingingConnection.State.Disconnected)
        val connectionState: StateFlow<RingingConnection.State> = _connectionState.asStateFlow()

        private val _activity = MutableStateFlow<String?>(null)
        val activity: StateFlow<String?> = _activity.asStateFlow()

        private val _pendingApprovals = MutableStateFlow<List<PendingApproval>>(emptyList())
        val pendingApprovals: StateFlow<List<PendingApproval>> = _pendingApprovals.asStateFlow()

        private val _sessionInfo = MutableStateFlow<com.qaqh.harness.data.ringing.model.SessionListEntry?>(null)
        val sessionInfo: StateFlow<com.qaqh.harness.data.ringing.model.SessionListEntry?> = _sessionInfo.asStateFlow()

        private val _sessions = MutableStateFlow<List<com.qaqh.harness.data.ringing.model.SessionListEntry>>(emptyList())
        val sessions: StateFlow<List<com.qaqh.harness.data.ringing.model.SessionListEntry>> = _sessions.asStateFlow()

        /** 立即刷新会话列表（进入列表屏/下拉刷新时调用）；未连接时等连接就绪后再拉（最多等 10s）。 */
        fun refreshNow(scope: kotlinx.coroutines.CoroutineScope) {
            val conn = connection ?: return
            // 调用方常是 UI scope（HomeScreen rememberCoroutineScope）→ 显式 IO
            scope.launch(Dispatchers.IO) {
                if (withTimeoutOrNull(10_000) { conn.state.first { it is RingingConnection.State.Connected } } == null) {
                    return@launch
                }
                refreshSessions(conn)
            }
        }

        private suspend fun refreshSessions(conn: RingingConnection): List<com.qaqh.harness.data.ringing.model.SessionListEntry> {
            val sid = (_connectionState.value as? RingingConnection.State.Connected)?.clientSessionId ?: run {
                android.util.Log.w("ConnectionService", "refresh: not connected yet")
                return emptyList()
            }
            val sessions = runCatching { withTimeoutOrNull(10_000) { conn.api.sessionList(sid) } }
                .onFailure { android.util.Log.w("ConnectionService", "sessionList failed", it) }
                .getOrNull() ?: return emptyList()
            // 过滤 daemon 内部临时会话（.tmp* 等，bootstrap 404）
            val visible = sessions.filterNot { it.sessionId.startsWith(".") }
            android.util.Log.i("ConnectionService", "sessions: ${sessions.size} raw, ${visible.size} visible")
            _sessions.value = visible
            return visible
        }

        /** 用户显式选择会话（进入聊天屏时调用），覆盖自动发现的选择。 */
        fun selectSession(sessionId: String) {
            connection?.setSessionId(sessionId)
            _sessionInfo.value = _sessions.value.firstOrNull { it.sessionId == sessionId }
        }

        @Volatile
        private var connection: RingingConnection? = null

        /** 会话屏直接借用当前连接（timeline/发消息）；未连接返回 null。 */
        fun activeConnection(): RingingConnection? = connection

        private val _lastRespondError = MutableStateFlow<String?>(null)
        val lastRespondError: StateFlow<String?> = _lastRespondError.asStateFlow()

        /**
         * 待办实况通知（聊天屏收集到 todo 变化时调用）：
         * API 36+ 用 Live Update ProgressStyle（N/M 完成进度条），低版本回退普通进度条。
         * 全部完成即取消；退出聊天屏后保留最后状态（SSE 只在屏内订阅，不会继续更新）。
         */
        fun updateTodoNotification(context: Context, todo: com.qaqh.harness.data.ringing.model.TodoState) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (todo.allDone) {
                nm.cancel(TODO_NOTIFICATION_ID)
                return
            }
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_TODO, "待办进度", NotificationManager.IMPORTANCE_LOW)
            )
            val pct = if (todo.total > 0) (todo.completed * 100) / todo.total else 0
            val current = todo.inProgress
                ?: todo.ordered().firstOrNull { it.status == com.qaqh.harness.data.ringing.model.TodoStatus.PENDING }
            val deepLink = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val title = "待办 ${todo.completed}/${todo.total}"
            val text = current?.let { "进行中：${it.title}" } ?: "等待开始"
            val notification = if (android.os.Build.VERSION.SDK_INT >= 36) {
                Notification.Builder(context, CHANNEL_TODO)
                    .setStyle(
                        android.app.Notification.ProgressStyle()
                            .addProgressSegment(
                                android.app.Notification.ProgressStyle.Segment(100).setColor(0xFF4F46E5.toInt())
                            )
                            .setProgress(pct)
                    )
                    .setContentTitle(title)
                    .setContentText(text)
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setContentIntent(deepLink)
                    .setOngoing(true)
                    .build()
            } else {
                NotificationCompat.Builder(context, CHANNEL_TODO)
                    .setProgress(100, pct, false)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setContentIntent(deepLink)
                    .setOngoing(true)
                    .build()
            }
            nm.notify(TODO_NOTIFICATION_ID, notification)
        }

        fun cancelTodoNotification(context: Context) {
            context.getSystemService(NotificationManager::class.java).cancel(TODO_NOTIFICATION_ID)
        }

        /** 设备凭据被 daemon 拒绝（吊销/token 轮换）：UI 消费后应掉回配对屏。 */
        private val _authRevoked = MutableStateFlow(false)
        val authRevoked: StateFlow<Boolean> = _authRevoked.asStateFlow()

        fun consumeAuthRevoked() {
            _authRevoked.value = false
        }

        /** 审批应答：按 kind 分派；当前阶段仍用 canonical id（后端 M0 后换 opaque challenge id）。 */
        suspend fun respondApproval(
            context: Context,
            approval: PendingApproval,
            approved: Boolean,
        ): Result<com.qaqh.harness.data.ringing.model.CommandAck> {
            val conn = connection ?: return Result.failure(IllegalStateException("未连接"))
            // 调用方是 UI scope，网络必须切 IO（否则 NetworkOnMainThreadException）
            val result = runCatching {
                withContext(Dispatchers.IO) { conn.respondInteraction(approval.kind, approval.interactionId, approval.callId, approved) }
            }
            result.onSuccess { ack ->
                if (ack.status != "accepted") {
                    _lastRespondError.value = "命令被拒绝：${ack.code ?: ""} ${ack.message ?: ""}"
                } else {
                    // 乐观移除：ack 已受理就先撤面板和通知，不等 SSE interaction_resolved
                    //（个别应答路径的事件归因链路后端仍在补，迟到/丢失会让面板僵住）
                    context.getSystemService(NotificationManager::class.java)
                        .cancel(APPROVAL_NOTIFICATION_BASE + approval.interactionId.hashCode())
                    _pendingApprovals.value =
                        _pendingApprovals.value.filterNot { it.interactionId == approval.interactionId }
                }
            }.onFailure { e ->
                _lastRespondError.value = "应答失败：${e.message ?: e.javaClass.simpleName}"
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

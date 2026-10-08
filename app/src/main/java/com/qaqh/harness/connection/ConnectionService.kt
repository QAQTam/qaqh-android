package com.qaqh.harness.connection

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationCompat.ProgressStyle
import com.qaqh.harness.MainActivity
import com.qaqh.harness.R
import com.qaqh.harness.data.pairing.CredentialStore
import com.qaqh.harness.data.pairing.pinnedHttpClient
import com.qaqh.harness.data.ringing.RingingApi
import com.qaqh.harness.data.ringing.RingingConnection
import com.qaqh.harness.data.ringing.model.TodoState
import com.qaqh.harness.data.ringing.model.ApprovalChallenge
import com.qaqh.harness.data.ringing.model.ApprovalDecisions
import com.qaqh.harness.data.ringing.model.InteractionKinds
import com.qaqh.harness.data.ringing.model.TodoStatus
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * 服务作用域的最后一道闸：任何逃逸出各循环 try/catch 的异常都在这里落地成日志。
 * 没有它，子协程（SupervisorJob 下失败不冒泡给父）的异常会走默认
 * [kotlinx.coroutines.CoroutineExceptionHandler] → 线程 uncaught handler → 进程被杀，
 * 表现为"放后台再进来就闪退"（真机 dropbox 里连排的同栈崩溃，见 SseSocketDeathTest）。
 */
internal val serviceExceptionGuard =
    kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
        android.util.Log.e("ConnectionService", "escaped service coroutine — kept alive", e)
    }

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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + serviceExceptionGuard)
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
        Companion.deviceScope = creds?.scope
        Companion.running = this
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
        scope.launch { todoTrackerLoop(conn) }
        conn.start()
    }

    override fun onDestroy() {
        scope.cancel()
        connection = null
        Companion.connection = null
        Companion.running = null
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
                // runStatus 兼容新旧两代 daemon（backend-fix 后 running:bool → status 词表）
                val target = sessions.firstOrNull { it.isWorking }
                    ?: sessions.maxByOrNull { it.updatedAt }
                if (target != null && target.sessionId != currentSessionId) {
                    currentSessionId = target.sessionId
                    _sessionInfo.value = target
                    conn.setSessionId(target.sessionId)
                }
            }
            // device 身份：待审批整体以 challenge 快照为准，每轮顺带刷新
            //（challenge_id 按 (kind, session, source) 去重签发，跨刷新稳定）
            if (deviceScope != null) {
                currentSessionId?.let { refreshChallenges(conn, it) }
            }
            delay(15_000)
        }
    }

    /**
     * 待办全局跟踪器：常驻 timeline SSE（服务端 hub 是 broadcast，同租约多连接各自收帧，
     * 与聊天屏的 SessionTimeline 互不影响）。每租约/换会话/daemon 重启（epoch 变）后首订
     * 全量回放重建 todo 状态，其后带 `{epoch}:timeline:{seq}` 游标只补尾巴。
     */
    private suspend fun todoTrackerLoop(conn: RingingConnection) {
        var lastSeq: Long? = null
        var trackedEpoch: String? = null
        var trackedSession: String? = null
        var todoState: TodoState? = null
        var backoffMs = 1_000L
        while (scope.isActive) {
            val sid = conn.leases.first()
            val sessionId = conn.currentSessionId() ?: run { delay(1_000); continue }
            val epoch = (conn.state.value as? RingingConnection.State.Connected)?.serverEpoch
            if (sessionId != trackedSession) {
                trackedSession = sessionId
                todoState = null
                lastSeq = null
            }
            val cursor = if (epoch != null && epoch == trackedEpoch && lastSeq != null) {
                "$epoch:timeline:$lastSeq"
            } else {
                null // epoch 变了或首订：全量回放重建
            }
            trackedEpoch = epoch
            var frames = 0
            try {
                conn.api.timelineEvents(sid, sessionId, cursor).collect { ev ->
                    when (ev) {
                        is com.qaqh.harness.data.ringing.RingingApi.TimelineSseEvent.Entry -> {
                            frames++
                            if (frames == 1) backoffMs = 1_000L // 流恢复，退避归位
                            lastSeq = maxOf(lastSeq ?: 0L, ev.frame.timelineSeq)
                            val next = ingestTodoFrame(conn, todoState, ev.frame)
                            if (next !== todoState) {
                                todoState = next
                                notifyTodo(this@ConnectionService, next)
                            }
                        }
                        com.qaqh.harness.data.ringing.RingingApi.TimelineSseEvent.Terminated -> Unit
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: RingingApi.ApiException) {
                android.util.Log.w(TAG, "todo tracker SSE ${e.httpStatus} ${e.code}")
            } catch (e: Exception) {
                // 断流（切后台被掐网 / daemon 重启 / 代理回收）给上来的多是裸
                // IOException/SocketException：只 catch ApiException 会让它逃出这个
                // launch，SupervisorJob 兜不住 → 线程 uncaught → 进程被杀（真机
                // dropbox 里成串的 FATAL EXCEPTION: DefaultDispatcher-worker-N）。
                // 这里必须按普通断流处理，退避后带游标重订阅。
                android.util.Log.w(TAG, "todo tracker SSE dropped: ${e.javaClass.simpleName} ${e.message}")
            }
            delay(backoffMs)
            backoffMs = minOf(backoffMs * 2, 30_000L)
        }
    }

    /** 从 timeline 帧摄取 todo 工具块（block_opened 内嵌 tool / tool_updated 顶层 tool）。 */
    private fun ingestTodoFrame(
        conn: RingingConnection,
        state: TodoState?,
        frame: com.qaqh.harness.data.ringing.model.TimelineEntryFrame,
    ): TodoState? {
        val type = frame.type ?: return state
        val data = frame.event as? JsonObject ?: return state
        val toolElement = when (type) {
            "block_opened" -> (data["block"] as? JsonObject)?.get("tool")
            "tool_updated" -> data["tool"]
            else -> null
        } ?: return state
        val tool = runCatching {
            conn.api.json.decodeFromJsonElement(
                com.qaqh.harness.data.ringing.model.TimelineTool.serializer(),
                toolElement,
            )
        }.getOrNull() ?: return state
        return TodoState.ingestTool(state, tool.name, tool.argsJson)
    }

    private suspend fun handleEvent(event: RingingConnection.SessionEvent) {        when (event) {
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
                if (deviceScope != null) {
                    // device 身份：canonical id 不出 daemon，SSE 只作立即刷新触发器
                    refreshChallengesNow()
                    return
                }
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
                if (deviceScope != null) {
                    refreshChallengesNow()
                    return
                }
                cancelApprovalCountdown(event.resolved.interactionId)
                _pendingApprovals.value =
                    _pendingApprovals.value.filterNot { it.interactionId == event.resolved.interactionId }
            }

            is RingingConnection.SessionEvent.InteractionExpired -> {
                if (deviceScope != null) {
                    refreshChallengesNow()
                    return
                }
                cancelApprovalCountdown(event.interactionId)
                _pendingApprovals.value =
                    _pendingApprovals.value.filterNot { it.interactionId == event.interactionId }
            }
        }
    }

    /** SSE 交互事件在 device 身份下的统一动作：立即拉 challenge 快照刷新列表。 */
    private suspend fun refreshChallengesNow() {
        val conn = connection ?: return
        val sid = conn.currentSessionId() ?: return
        refreshChallenges(conn, sid)
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
        // 实时活动胶囊要求 channel importance 非 MIN/LOW（Google 示例用 DEFAULT）
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_TODO, "待办进度", NotificationManager.IMPORTANCE_DEFAULT)
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

        private val _workspaces = MutableStateFlow<List<com.qaqh.harness.data.ringing.model.WorkspaceEntry>>(emptyList())
        val workspaces: StateFlow<List<com.qaqh.harness.data.ringing.model.WorkspaceEntry>> = _workspaces.asStateFlow()

        private val _refreshing = MutableStateFlow(false)
        /** 手动/进入时刷新是否进行中（Home 下拉指示器接此状态）。 */
        val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

        /** 立即刷新会话列表（进入列表屏/下拉刷新时调用）；未连接时等连接就绪后再拉（最多等 10s）。 */
        fun refreshNow(scope: kotlinx.coroutines.CoroutineScope) {
            val conn = connection ?: return
            // 调用方常是 UI scope（HomeScreen rememberCoroutineScope）→ 显式 IO
            scope.launch(Dispatchers.IO) {
                if (withTimeoutOrNull(10_000) { conn.state.first { it is RingingConnection.State.Connected } } == null) {
                    return@launch
                }
                _refreshing.value = true
                refreshSessions(conn)
                _refreshing.value = false
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
            // 工作区注册表：读接口，失败静默（旧 daemon 无此方法；Home 退化为单 section）
            val workspaces = runCatching { withTimeoutOrNull(10_000) { conn.api.workspaceList(sid) } }
                .onFailure { android.util.Log.w(TAG, "workspaceList failed: $it") }
                .getOrNull()
            if (workspaces != null) _workspaces.value = workspaces
            return visible
        }

        /** 用户显式选择会话（进入聊天屏时调用），覆盖自动发现的选择。 */
        fun selectSession(sessionId: String) {
            connection?.setSessionId(sessionId)
            _sessionInfo.value = _sessions.value.firstOrNull { it.sessionId == sessionId }
        }

        @Volatile
        private var connection: RingingConnection? = null

        @Volatile
        private var running: ConnectionService? = null

        /** /pair 签发的 scope（非 null 即 device 身份，扫码配对得来）：审批走 challenge 流。 */
        @Volatile
        private var deviceScope: String? = null

        /** 会话屏直接借用当前连接（timeline/发消息）；未连接返回 null。 */
        fun activeConnection(): RingingConnection? = connection

        private val _lastRespondError = MutableStateFlow<String?>(null)
        val lastRespondError: StateFlow<String?> = _lastRespondError.asStateFlow()

        /**
         * 待办实况通知（Android 16 Live Updates / ColorOS 16 流体云胶囊）。
         * 由 [todoTrackerLoop] 全局驱动（不再依赖聊天屏打开）：
         * - NotificationCompat + setRequestPromotedOngoing(true) 请求胶囊提升呈现
         *   （前提：manifest 声明 POST_PROMOTED_NOTIFICATIONS + 用户在系统设置允许，
         *   见 [promotedOngoingEnabled]；Google platform-samples live-updates 同款配方）
         * - API 36+ 用 ProgressStyle 分段着色（已完成绿 / 剩余灰），setShortCriticalText
         *   供胶囊收起态显示 N/M；低版本回退普通进度条
         * - 全部完成/无待办即取消
         */
        private fun notifyTodo(context: Context, todo: TodoState?) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (todo == null || todo.items.isEmpty() || todo.allDone) {
                nm.cancel(TODO_NOTIFICATION_ID)
                return
            }
            val deepLink = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val pct = if (todo.total > 0) (todo.completed * 100) / todo.total else 0
            val current = todo.inProgress
                ?: todo.ordered().firstOrNull { it.status == TodoStatus.PENDING }
            val builder = NotificationCompat.Builder(context, CHANNEL_TODO)
                .setSmallIcon(R.drawable.ic_stat_todo)
                .setOngoing(true)
                .setRequestPromotedOngoing(true)
                .setContentTitle("待办 ${todo.completed}/${todo.total}")
                .setContentText(current?.let { "进行中：${it.title}" } ?: "等待开始")
                .setShortCriticalText("${todo.completed}/${todo.total}")
                .setContentIntent(deepLink)
                .setOnlyAlertOnce(true)
            if (android.os.Build.VERSION.SDK_INT >= 36) {
                val segments = buildList {
                    val done = (todo.completed * 100) / todo.total
                    if (done > 0) add(ProgressStyle.Segment(done).setColor(0xFF188038.toInt()))
                    if (done < 100) add(ProgressStyle.Segment(100 - done).setColor(0xFF3C4043.toInt()))
                }
                builder.setStyle(
                    ProgressStyle()
                        .setStyledByProgress(false)
                        .setProgress(100)
                        .setProgressSegments(segments)
                )
            } else {
                builder.setProgress(100, pct, false)
            }
            nm.notify(TODO_NOTIFICATION_ID, builder.build())
        }

        /** 实时活动胶囊（promoted ongoing）是否已被允许发布（API 36+；ColorOS 16 流体云的前提）。 */
        fun promotedOngoingEnabled(context: Context): Boolean =
            android.os.Build.VERSION.SDK_INT >= 36 &&
                context.getSystemService(NotificationManager::class.java).canPostPromotedNotifications()

        /** 跳系统"提升的通知/实时活动"设置页（Google platform-samples live-updates 同款 intent）。 */
        fun openPromotedSettings(context: Context) {
            if (android.os.Build.VERSION.SDK_INT < 36) return
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            )
        }

        private const val TAG = "ConnectionService"

        /** 设备凭据被 daemon 拒绝（吊销/token 轮换）：UI 消费后应掉回配对屏。 */
        private val _authRevoked = MutableStateFlow(false)
        val authRevoked: StateFlow<Boolean> = _authRevoked.asStateFlow()

        fun consumeAuthRevoked() {
            _authRevoked.value = false
        }

        /** 消费审批应答错误（Snackbar 展示后调用，避免同一条重复触发）。 */
        fun consumeRespondError() {
            _lastRespondError.value = null
        }

        /** 审批应答：按 kind 分派；当前阶段仍用 canonical id（后端 M0 后换 opaque challenge id）。 */
        /**
         * device 身份的待审批投影源：GET /approvals 的 challenge 快照。
         * canonical id 不出 daemon——SSE interaction_requested 的 id 无法与 challenge
         * 关联、更不能用它应答，device 模式待审批列表**整体以快照为准**，SSE 只作
         * 立即刷新的触发器。daemon 按 (kind, session, source) 去重签发，challenge_id
         * 跨刷新稳定。刷新时机 = 会话发现循环每 15s + SSE 交互事件 + 应答成功后。
         */
        private suspend fun refreshChallenges(conn: RingingConnection, sessionId: String) {
            val lease = (_connectionState.value as? RingingConnection.State.Connected)?.clientSessionId
                ?: return
            val approvals = runCatching {
                withTimeoutOrNull(10_000) { conn.api.approvals(lease, sessionId) }
            }.onFailure {
                android.util.Log.w(TAG, "challenges refresh failed: $it")
            }.getOrNull() ?: return
            val svc = running
            val mapped = approvals.challenges.map { c ->
                val kind = when (c.kind) {
                    ApprovalChallenge.KIND_TOOL_PERMISSION -> InteractionKinds.PERMISSION
                    ApprovalChallenge.KIND_ASK -> InteractionKinds.ASK
                    ApprovalChallenge.KIND_PLAN -> InteractionKinds.PLAN
                    else -> c.kind
                }
                val previous = _pendingApprovals.value.firstOrNull { it.interactionId == c.challengeId }
                PendingApproval(
                    interactionId = c.challengeId,
                    kind = kind,
                    callId = null,
                    summary = previous?.summary ?: challengeSummaryOf(c, svc),
                    // 首次签发时算的到期时间保留，避免每轮刷新把通知倒计时重置
                    expiresAtMs = previous?.expiresAtMs
                        ?: c.expiresInSeconds?.let { System.currentTimeMillis() + it * 1000 },
                )
            }
            val previousList = _pendingApprovals.value
            _pendingApprovals.value = mapped
            // 新出现的 challenge 发通知；消失的（已消费/过期）撤面板与通知
            mapped.filter { m -> previousList.none { it.interactionId == m.interactionId } }
                .forEach { svc?.notifyApproval(it) }
            previousList.filter { e -> mapped.none { it.interactionId == e.interactionId } }
                .forEach { e ->
                    svc?.cancelApprovalCountdown(e.interactionId)
                    svc?.getSystemService(NotificationManager::class.java)
                        ?.cancel(APPROVAL_NOTIFICATION_BASE + e.interactionId.hashCode())
                }
        }

        /** challenge details → 摘要。permission 是白名单字段投影；ask/plan 是交互正文原样。 */
        private fun challengeSummaryOf(c: ApprovalChallenge, svc: ConnectionService?): String = when (c.kind) {
            ApprovalChallenge.KIND_TOOL_PERMISSION -> {
                val d = c.permissionDetails
                listOfNotNull(
                    d?.toolName,
                    d?.actionSummary ?: d?.reason,
                    d?.paths?.firstOrNull(),
                ).joinToString(" · ").ifBlank { "工具调用请求" }
            }
            ApprovalChallenge.KIND_ASK -> svc?.askSummaryOf(c.details) ?: "提问"
            else -> (c.details as? JsonObject)?.let { o ->
                listOf("plan", "text", "summary", "title").firstNotNullOfOrNull { key ->
                    (o[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                }
            } ?: "计划评审"
        }

        /**
         * 审批应答：按身份分派。
         * - device 身份（扫码配对）：走 challenge 流 `POST /approvals/respond`——一次性
         *   opaque challenge_id（即 [PendingApproval.interactionId]），canonical id 不出
         *   daemon，命令通道应答对 device 走不通。
         * - admin/Direct（手输配对）：维持 canonical 命令通道（respondInteraction）。
         */
        suspend fun respondApproval(
            context: Context,
            approval: PendingApproval,
            approved: Boolean,
        ): Result<com.qaqh.harness.data.ringing.model.CommandAck> {
            val conn = connection ?: return Result.failure(IllegalStateException("未连接"))
            val sessionId = conn.currentSessionId()
                ?: return Result.failure(IllegalStateException("无目标会话"))
            val isDevice = deviceScope != null
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    if (isDevice) {
                        val lease = (_connectionState.value as? RingingConnection.State.Connected)?.clientSessionId
                            ?: error("未连接")
                        val decision = when (approval.kind) {
                            InteractionKinds.ASK -> ApprovalDecisions.DISMISS
                            else -> if (approved) ApprovalDecisions.APPROVE else ApprovalDecisions.REJECT
                        }
                        conn.api.approvalRespond(lease, sessionId, approval.interactionId, decision)
                    } else {
                        conn.respondInteraction(approval.kind, approval.interactionId, approval.callId, approved)
                    }
                }
            }
            result.onSuccess { ack ->
                if (ack.status != "accepted") {
                    _lastRespondError.value = when (ack.code) {
                        "interaction_already_resolved" -> "该请求已被处理（电脑端已应答）"
                        "insufficient_scope" -> "权限不足：当前设备档位无法执行该操作"
                        else -> "命令被拒绝：${ack.code ?: ""} ${ack.message ?: ""}"
                    }
                } else {
                    // 乐观移除：ack 已受理就先撤面板和通知，不等可能迟到的 SSE resolution
                    context.getSystemService(NotificationManager::class.java)
                        .cancel(APPROVAL_NOTIFICATION_BASE + approval.interactionId.hashCode())
                    _pendingApprovals.value =
                        _pendingApprovals.value.filterNot { it.interactionId == approval.interactionId }
                    if (isDevice) {
                        // challenge 已一次性消费：立即刷新快照（回收空位/承接新签发）
                        runCatching { refreshChallenges(conn, sessionId) }
                    }
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

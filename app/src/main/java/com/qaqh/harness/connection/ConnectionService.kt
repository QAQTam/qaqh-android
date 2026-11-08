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
import com.qaqh.harness.data.core.CoreConnection
import com.qaqh.harness.data.core.CoreFrame
import com.qaqh.harness.data.core.CoreJson
import com.qaqh.harness.data.core.SessionEvent as ContractSessionEvent
import com.qaqh.harness.data.pairing.CredentialStore
import com.qaqh.harness.data.ringing.model.ApprovalChallenge
import com.qaqh.harness.data.ringing.model.ApprovalDecisions
import com.qaqh.harness.data.ringing.model.AskAnswer
import com.qaqh.harness.data.ringing.model.AskForm
import com.qaqh.harness.data.ringing.model.AskSubmission
import com.qaqh.harness.data.ringing.model.InteractionKinds
import com.qaqh.harness.data.ringing.model.InteractionResolved
import com.qaqh.harness.data.ringing.model.SessionListEntry
import com.qaqh.harness.data.ringing.model.TodoState
import com.qaqh.harness.data.ringing.model.TodoStatus
import com.qaqh.harness.data.ringing.model.CommandAck
import com.qaqh.harness.data.ringing.model.WorkspaceEntry
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * 服务作用域的最后一道闸:任何逃逸出各循环 try/catch 的异常都在这里落地成日志。
 * 没有它,子协程(SupervisorJob 下失败不冒泡给父)的异常会走默认 handler → 进程被杀。
 */
internal val serviceExceptionGuard =
    kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
        android.util.Log.e("ConnectionService", "escaped service coroutine — kept alive", e)
    }

/** 当前待审批(kind=permission/ask/plan)投影。 */
data class PendingApproval(
    val sessionId: String,
    val interactionId: String,
    /** permission | ask | plan */
    val kind: String,
    val callId: String?,
    val summary: String,
    val expiresAtMs: Long?,
    val detailsUnavailable: Boolean = false,
    val askForm: AskForm? = null,
)

/**
 * 常驻前台服务:持有 [CoreConnection](Rust FFI 传输层),把会话流事件投影为
 * 连接状态 / 会话活动 / 待办 / 待审批四张全局 StateFlow,UI 层直接收集。
 *
 * 传输知识(租约续租、退避重连、SSE、TLS pin)全部在 Rust core;本服务只做
 * 投影与通知。会话发现:每 15s 拉 session.list,取 working 会话(否则最近更新),
 * session_id 变化时切流。device 身份的审批以 challenge 快照为准(SSE 只作刷新触发器)。
 */
class ConnectionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + serviceExceptionGuard)
    private var connection: CoreConnection? = null
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
        val conn = CoreConnection(
            endpoint = creds.baseUrl,
            token = creds.token,
            tlsFingerprint = creds.tlsFingerprint, // https 自签:指纹 pin 在 Rust 侧注入
            scope = scope,
        )
        connection = conn
        Companion.connection = conn
        scope.launch { conn.events.collect { handleEvent(it) } } // 逐事件串行处理
        scope.launch { trackTodos(conn) }
        scope.launch { _todos.collect { notifyTodo(this@ConnectionService, it) } }
        scope.launch {
            var unauthorizedFailures = 0
            conn.state.collect { st ->
                if (st is CoreConnection.State.Failed && st.unauthorized) {
                    // 连续 2 次真鉴权失败才认定吊销(单次 401 可能是竞态误报,wipe 不可逆)
                    unauthorizedFailures++
                    if (unauthorizedFailures < 2) {
                        notifyStatus("认证异常,重试中…")
                        return@collect
                    }
                    android.util.Log.w(TAG, "repeated 401 from daemon — device token invalid/revoked, unpairing")
                    CredentialStore(this@ConnectionService).clear()
                    _authRevoked.value = true
                    stopSelf()
                    return@collect
                }
                unauthorizedFailures = 0
                _connectionState.value = st
                notifyStatus(
                    when (st) {
                        is CoreConnection.State.Connected -> "已连接电脑端"
                        is CoreConnection.State.Connecting -> "正在连接电脑端…"
                        is CoreConnection.State.Failed -> "连接失败,将自动重试"
                        CoreConnection.State.Disconnected -> "已断开"
                    }
                )
            }
        }
        scope.launch { sessionDiscoveryLoop(conn) }
        conn.start()
    }

    override fun onDestroy() {
        scope.cancel()
        connection?.client?.shutdown() // 主循环在 service scope 里,scope.cancel 已停编排
        connection = null
        Companion.connection = null
        Companion.running = null
        _connectionState.value = CoreConnection.State.Disconnected
        _activity.value = null
        _pendingApprovals.value = emptyList()
        _todos.value = null
        super.onDestroy()
    }

    /** 每 15s 发现一次活跃会话;session_id 变化时切换事件流。 */
    private suspend fun sessionDiscoveryLoop(conn: CoreConnection) {
        var currentSessionId: String? = null
        while (scope.isActive) {
            // 先等连接就绪、立刻首拉,再进周期:冷启动列表 ~1-2s 可见
            conn.state.first { it is CoreConnection.State.Connected }
            val sessions = refreshSessions(conn)
            if (sessions.isNotEmpty()) {
                val target = sessions.firstOrNull { it.isWorking }
                    ?: sessions.maxByOrNull { it.updatedAt }
                if (target != null && target.sessionId != currentSessionId) {
                    currentSessionId = target.sessionId
                    _sessionInfo.value = target
                    conn.setSessionId(target.sessionId)
                }
            }
            // device 身份:待审批整体以 challenge 快照为准,每轮顺带刷新
            if (deviceScope != null) {
                conn.currentSessionId()?.let { refreshChallenges(conn, it) }
            }
            delay(15_000)
        }
    }

    /** 待办实况:当前会话流里的 todo 工具事件 → TodoState(全局通知驱动;切会话重置)。 */
    private suspend fun trackTodos(conn: CoreConnection) {
        conn.sessionSeed.collectLatest { seed ->
            if (seed == null) return@collectLatest
            _todos.value = null
            conn.client.frames
                .filter { it.sessionId == seed }
                .collect { frame ->
                    when (val e = frame.event) {
                        is ContractSessionEvent.ToolDeclared ->
                            _todos.value = TodoState.ingestTool(
                                _todos.value,
                                e.toolName,
                                e.args?.toString(),
                            ) ?: _todos.value
                        else -> Unit
                    }
                }
        }
    }

    private suspend fun handleEvent(event: CoreConnection.SessionEvent) {
        when (event) {
            is CoreConnection.SessionEvent.Activity -> {
                _activity.value = event.state
                notifyStatus(
                    when (event.state) {
                        "running" -> "会话正在工作…"
                        "idle" -> "会话空闲"
                        "waiting_user" -> "等待你的输入"
                        "cancelling" -> "正在取消…"
                        else -> "会话活动:${event.state}"
                    }
                )
            }

            is CoreConnection.SessionEvent.InteractionRequested -> {
                if (deviceScope != null) {
                    // device 身份:canonical id 不出 daemon,SSE 只作立即刷新触发器
                    refreshChallengesNow()
                    return
                }
                val r = event.requested
                val conn = connection ?: return
                if (conn.currentSessionId() != r.sessionId) return
                val text = interactionText(conn, r.request)
                if (conn.currentSessionId() != r.sessionId) return
                val body = text?.let { runCatching { CoreJson.parseToJsonElement(it) }.getOrNull() }
                val ask = if (r.kind == InteractionKinds.ASK) decodeAskForm(body) else null
                val permission = if (r.kind == InteractionKinds.PERMISSION) permissionSummary(body) else null
                val approval = PendingApproval(
                    sessionId = r.sessionId,
                    interactionId = r.interactionId,
                    kind = r.kind,
                    callId = r.callId,
                    summary = ask?.summary ?: when (r.kind) {
                        InteractionKinds.PERMISSION -> permission ?: "工具调用请求"
                        InteractionKinds.PLAN -> text ?: "计划评审"
                        else -> "提问"
                    },
                    expiresAtMs = r.expiresAtMs,
                    detailsUnavailable = when (r.kind) {
                        InteractionKinds.PERMISSION -> permission == null
                        InteractionKinds.ASK -> ask == null
                        else -> text.isNullOrBlank()
                    },
                    askForm = ask,
                )
                val previous = _pendingApprovals.value
                _pendingApprovals.value = previous.filterNot { it.interactionId == r.interactionId } + approval
                if (previous.none { it.interactionId == r.interactionId }) notifyApproval(approval)
            }

            is CoreConnection.SessionEvent.InteractionResolved -> {
                if (deviceScope != null) {
                    refreshChallengesNow()
                    return
                }
                cancelApprovalCountdown(event.resolved.interactionId)
                _pendingApprovals.value =
                    _pendingApprovals.value.filterNot { it.interactionId == event.resolved.interactionId }
            }

            is CoreConnection.SessionEvent.InteractionExpired -> {
                if (deviceScope != null) {
                    refreshChallengesNow()
                    return
                }
                cancelApprovalCountdown(event.interactionId)
                _pendingApprovals.value =
                    _pendingApprovals.value.filterNot { it.interactionId == event.interactionId }
            }

            is CoreConnection.SessionEvent.DriverChanged,
            is CoreConnection.SessionEvent.SessionTitle,
            -> Unit
        }
    }

    /** SSE 交互事件在 device 身份下的统一动作:立即拉 challenge 快照刷新列表。 */
    private suspend fun refreshChallengesNow() {
        val conn = connection ?: return
        val sid = conn.currentSessionId() ?: return
        refreshChallenges(conn, sid)
    }

    private suspend fun interactionText(
        conn: CoreConnection,
        request: JsonElement?,
    ): String? {
        val body = request as? JsonObject ?: return null
        val data = body["data"] as? JsonObject ?: return null
        return when ((body["kind"] as? JsonPrimitive)?.content) {
            "inline" -> (data["text"] as? JsonPrimitive)?.content
            "ref" -> data["content_ref"]?.let { ref ->
                runCatching { withTimeoutOrNull(5_000) { conn.client.contentText(ref.toString()) } }
                    .getOrNull()
            }
            else -> null
        }
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

    fun notifyApproval(approval: PendingApproval) {
        ensureChannels()
        if (android.os.Build.VERSION.SDK_INT >= 36 && approval.expiresAtMs != null) {
            startApprovalCountdown(approval)
        } else {
            postApprovalNotification(approval, liveUpdatePct = null)
        }
    }

    /** Android 16 Live Update:带剩余时间进度条的审批通知,每秒刷新直到 resolved/expired。 */
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
        // 实时活动胶囊要求 channel importance 非 MIN/LOW
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
        private const val TAG = "ConnectionService"

        private val _connectionState = MutableStateFlow<CoreConnection.State>(CoreConnection.State.Disconnected)
        val connectionState: StateFlow<CoreConnection.State> = _connectionState.asStateFlow()

        private val _activity = MutableStateFlow<String?>(null)
        val activity: StateFlow<String?> = _activity.asStateFlow()

        private val _pendingApprovals = MutableStateFlow<List<PendingApproval>>(emptyList())
        val pendingApprovals: StateFlow<List<PendingApproval>> = _pendingApprovals.asStateFlow()

        private val _todos = MutableStateFlow<TodoState?>(null)
        val todos: StateFlow<TodoState?> = _todos.asStateFlow()

        private val _sessionInfo = MutableStateFlow<SessionListEntry?>(null)
        val sessionInfo: StateFlow<SessionListEntry?> = _sessionInfo.asStateFlow()

        private val _sessions = MutableStateFlow<List<SessionListEntry>>(emptyList())
        val sessions: StateFlow<List<SessionListEntry>> = _sessions.asStateFlow()

        private val _workspaces = MutableStateFlow<List<WorkspaceEntry>>(emptyList())
        val workspaces: StateFlow<List<WorkspaceEntry>> = _workspaces.asStateFlow()

        private val _refreshing = MutableStateFlow(false)
        /** 手动/进入时刷新是否进行中(Home 下拉指示器接此状态)。 */
        val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

        /** 立即刷新会话列表(进入列表屏/下拉刷新时调用);未连接时等连接就绪后再拉(最多等 10s)。 */
        fun refreshNow(scope: kotlinx.coroutines.CoroutineScope) {
            val conn = connection ?: return
            scope.launch(Dispatchers.IO) {
                if (withTimeoutOrNull(10_000) { conn.state.first { it is CoreConnection.State.Connected } } == null) {
                    return@launch
                }
                _refreshing.value = true
                refreshSessions(conn)
                _refreshing.value = false
            }
        }

        private suspend fun refreshSessions(conn: CoreConnection): List<SessionListEntry> {
            val sessions = runCatching {
                withTimeoutOrNull(10_000) { conn.client.sessionList() }
            }.onFailure { android.util.Log.w(TAG, "sessionList failed: ${it.message}") }
                .getOrNull() ?: return emptyList()
            // 过滤 daemon 内部临时会话(.tmp* 等,bootstrap 404)
            val visible = sessions.filterNot { it.sessionId.startsWith(".") }
            _sessions.value = visible
            val workspaces = runCatching {
                withTimeoutOrNull(10_000) { conn.client.workspaceList() }
            }.onFailure { android.util.Log.w(TAG, "workspaceList failed: ${it.message}") }
                .getOrNull()
            if (workspaces != null) _workspaces.value = workspaces
            return visible
        }

        /** 用户显式选择会话(进入聊天屏时调用),覆盖自动发现的选择。 */
        fun selectSession(sessionId: String) {
            connection?.setSessionId(sessionId)
            _sessionInfo.value = _sessions.value.firstOrNull { it.sessionId == sessionId }
        }

        @Volatile
        private var connection: CoreConnection? = null

        @Volatile
        private var running: ConnectionService? = null

        /** /pair 签发的 scope(非 null 即 device 身份,扫码配对得来):审批走 challenge 流。 */
        @Volatile
        private var deviceScope: String? = null

        /** 聊天屏直接借用当前连接(timeline/发消息);未连接返回 null。 */
        fun activeConnection(): CoreConnection? = connection

        private val _lastRespondError = MutableStateFlow<String?>(null)
        val lastRespondError: StateFlow<String?> = _lastRespondError.asStateFlow()

        /** 设备凭据被 daemon 拒绝(吊销/token 轮换):UI 消费后应掉回配对屏。 */
        private val _authRevoked = MutableStateFlow(false)
        val authRevoked: StateFlow<Boolean> = _authRevoked.asStateFlow()

        fun consumeAuthRevoked() {
            _authRevoked.value = false
        }

        /** 消费审批应答错误(Snackbar 展示后调用,避免同一条重复触发)。 */
        fun consumeRespondError() {
            _lastRespondError.value = null
        }

        /**
         * device 身份的待审批投影源:GET /approvals 的 challenge 快照。
         * canonical id 不出 daemon——device 模式待审批列表**整体以快照为准**,SSE 只作
         * 立即刷新的触发器。刷新时机 = 会话发现循环每 15s + SSE 交互事件 + 应答成功后。
         */
        private val challengeRefresh = Mutex()

        private suspend fun refreshChallenges(conn: CoreConnection, sessionId: String) = challengeRefresh.withLock {
            if (_connectionState.value !is CoreConnection.State.Connected ||
                conn.currentSessionId() != sessionId
            ) return@withLock
            val approvals = runCatching {
                withTimeoutOrNull(10_000) { conn.client.deviceChallenges(sessionId) }
            }.onFailure {
                android.util.Log.w(TAG, "challenges refresh failed: ${it.message}")
            }.getOrNull() ?: return@withLock
            if (conn.currentSessionId() != sessionId || connection !== conn) return@withLock
            val svc = running
            val mapped = approvals.map { c ->
                val kind = when (c.kind) {
                    ApprovalChallenge.KIND_TOOL_PERMISSION -> InteractionKinds.PERMISSION
                    ApprovalChallenge.KIND_ASK -> InteractionKinds.ASK
                    ApprovalChallenge.KIND_PLAN -> InteractionKinds.PLAN
                    else -> c.kind
                }
                val previous = _pendingApprovals.value.firstOrNull { it.interactionId == c.challengeId }
                val ask = if (kind == InteractionKinds.ASK) decodeAskForm(c.details) else null
                PendingApproval(
                    sessionId = sessionId,
                    interactionId = c.challengeId,
                    kind = kind,
                    callId = null,
                    summary = ask?.summary ?: challengeSummaryOf(c),
                    // 首次签发时算的到期时间保留,避免每轮刷新把通知倒计时重置
                    expiresAtMs = previous?.expiresAtMs
                        ?: c.expiresInSeconds?.let { System.currentTimeMillis() + it * 1000 },
                    detailsUnavailable = c.detailsUnavailable || (kind == InteractionKinds.ASK && ask == null),
                    askForm = ask,
                )
            }
            val previousList = _pendingApprovals.value
            _pendingApprovals.value = mapped
            // 新出现的 challenge 发通知;消失的(已消费/过期)撤面板与通知
            mapped.filter { m -> previousList.none { it.interactionId == m.interactionId } }
                .forEach { svc?.notifyApproval(it) }
            previousList.filter { e -> mapped.none { it.interactionId == e.interactionId } }
                .forEach { e ->
                    svc?.cancelApprovalCountdown(e.interactionId)
                    svc?.getSystemService(NotificationManager::class.java)
                        ?.cancel(APPROVAL_NOTIFICATION_BASE + e.interactionId.hashCode())
                }
        }

        /** challenge details → 摘要。permission 是白名单字段投影;ask/plan 是交互正文原样。 */
        private fun challengeSummaryOf(c: ApprovalChallenge): String = when (c.kind) {
            ApprovalChallenge.KIND_TOOL_PERMISSION -> {
                val d = c.permissionDetails
                listOfNotNull(
                    d?.toolName,
                    d?.actionSummary ?: d?.reason,
                    d?.paths?.firstOrNull(),
                ).joinToString(" · ").ifBlank { "工具调用请求" }
            }
            ApprovalChallenge.KIND_ASK -> "提问"
            else -> (c.details as? JsonObject)?.let { o ->
                listOf("plan", "text", "summary", "title").firstNotNullOfOrNull { key ->
                    (o[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                }
            } ?: "计划评审"
        }

        /**
         * 审批应答:按身份分派。
         * - device 身份(扫码配对):走 challenge 流(一次性 opaque challenge_id);
         * - admin/Direct(手输配对):维持 canonical 命令通道(respondInteraction)。
         */
        suspend fun respondApproval(
            context: Context,
            approval: PendingApproval,
            approved: Boolean,
            answers: List<AskAnswer>? = null,
        ): Result<CommandAck> {
            val conn = connection
            val sessionId = approval.sessionId
            val isDevice = deviceScope != null
            val result = runCatching {
                check(conn != null && conn.currentSessionId() == sessionId) { "审批所属会话已切换" }
                check(!approval.detailsUnavailable || (!approved && answers == null)) { "审批详情不可用" }
                withContext(Dispatchers.IO) {
                    if (isDevice) {
                        val decision = when (approval.kind) {
                            InteractionKinds.ASK ->
                                if (answers == null) ApprovalDecisions.DISMISS else ApprovalDecisions.SUBMIT
                            else -> if (approved) ApprovalDecisions.APPROVE else ApprovalDecisions.REJECT
                        }
                        val payload = answers?.let { CoreJson.encodeToString(AskSubmission(it)) }
                        conn.client.respondDeviceApproval(sessionId, approval.interactionId, decision, payload)
                    } else {
                        conn.respondInteraction(
                            sessionId, approval.kind, approval.interactionId, approval.callId, approved,
                            answers?.let { CoreJson.encodeToString(it) },
                        )
                    }
                }
            }
            result.onSuccess { ack ->
                if (ack.status != "accepted") {
                    _lastRespondError.value = when (ack.code) {
                        "interaction_already_resolved" -> "该请求已被处理(电脑端已应答)"
                        "insufficient_scope" -> "权限不足:当前设备档位无法执行该操作"
                        else -> "命令被拒绝:${ack.code ?: ""} ${ack.message ?: ""}"
                    }
                } else {
                    // 乐观移除:ack 已受理就先撤面板和通知,不等可能迟到的 SSE resolution
                    context.getSystemService(NotificationManager::class.java)
                        .cancel(APPROVAL_NOTIFICATION_BASE + approval.interactionId.hashCode())
                    _pendingApprovals.value =
                        _pendingApprovals.value.filterNot { it.interactionId == approval.interactionId }
                }
            }.onFailure { e ->
                _lastRespondError.value = "应答失败:${e.message ?: e.javaClass.simpleName}"
            }
            // 服务端先消费 challenge 再处理命令，应答失败时也需要刷新。
            if (isDevice && conn != null) refreshChallenges(conn, sessionId)
            return result
        }

        private fun decodeAskForm(body: JsonElement?): AskForm? =
            body?.let { runCatching { CoreJson.decodeFromJsonElement<AskForm>(it) }.getOrNull() }
                ?.takeIf { it.questions.isNotEmpty() }

        private fun permissionSummary(body: JsonElement?): String? {
            val details = body as? JsonObject ?: return null
            val tool = (details["tool_name"] as? JsonPrimitive)?.content ?: return null
            val description = ((details["action_summary"] ?: details["reason"]) as? JsonPrimitive)?.content
                ?: return null
            return "$tool · $description"
        }

        /** 待办实况通知(Android 16 Live Updates / ColorOS 16 流体云胶囊)。 */
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
                .setContentText(current?.let { "进行中:${it.title}" } ?: "等待开始")
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

        /** 实时活动胶囊(promoted ongoing)是否已被允许发布(API 36+)。 */
        fun promotedOngoingEnabled(context: Context): Boolean =
            android.os.Build.VERSION.SDK_INT >= 36 &&
                context.getSystemService(NotificationManager::class.java).canPostPromotedNotifications()

        /** 跳系统"提升的通知/实时活动"设置页。 */
        fun openPromotedSettings(context: Context) {
            if (android.os.Build.VERSION.SDK_INT < 36) return
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            )
        }

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ConnectionService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ConnectionService::class.java))
        }
    }
}

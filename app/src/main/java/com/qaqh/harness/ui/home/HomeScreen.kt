package com.qaqh.harness.ui.home

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qaqh.harness.connection.ConnectionService
import com.qaqh.harness.data.pairing.StoredCredentials
import com.qaqh.harness.data.ringing.RingingConnection
import com.qaqh.harness.ui.nav.GlassNavBarContentPadding
import kotlinx.coroutines.launch

// ---- 布局常量：全屏统一，禁止散落魔数 ----
private val ScreenPadding = 16.dp
private val BlockSpacing = 12.dp
private val CardInnerPadding = 16.dp
private val CardCorner = 24.dp
private val StatusDotSize = 10.dp

/** 会话活动状态 → 状态点颜色/文案。 */
private data class ActivityPresentation(val color: androidx.compose.ui.graphics.Color, val label: String)

@Composable
private fun presentActivity(state: String?): ActivityPresentation = when (state) {
    "running", "working" -> ActivityPresentation(MaterialTheme.colorScheme.primary, "工作中")
    "idle" -> ActivityPresentation(MaterialTheme.colorScheme.outline, "空闲")
    "interrupted", "canceled" -> ActivityPresentation(MaterialTheme.colorScheme.error, "已中断")
    // session.list 新词表的等待态（backend-fix 后 status 取代 running:bool）
    "waiting_permission" -> ActivityPresentation(MaterialTheme.colorScheme.tertiary, "等待授权")
    "waiting_ask" -> ActivityPresentation(MaterialTheme.colorScheme.tertiary, "等待回答")
    "waiting_plan" -> ActivityPresentation(MaterialTheme.colorScheme.tertiary, "等待评审")
    "error" -> ActivityPresentation(MaterialTheme.colorScheme.error, "出错")
    null, "not_running" -> ActivityPresentation(MaterialTheme.colorScheme.outline, "无活动")
    else -> ActivityPresentation(MaterialTheme.colorScheme.tertiary, state)
}

@Composable
fun HomeScreen(
    credentials: StoredCredentials,
    onOpenSession: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by ConnectionService.connectionState.collectAsState()
    val activity by ConnectionService.activity.collectAsState()
    val session by ConnectionService.sessionInfo.collectAsState()
    val sessions by ConnectionService.sessions.collectAsState()
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(false) }

    // 进入列表屏立即刷新一次（不等 15s 兜底轮询）
    LaunchedEffect(Unit) { ConnectionService.refreshNow(scope) }

    fun createSession() {
        val conn = ConnectionService.activeConnection() ?: return
        creating = true
        scope.launch {
            runCatching { conn.createSession() }
            ConnectionService.refreshNow(scope)
            creating = false
        }
    }

    PullToRefreshBox(
        isRefreshing = false,
        onRefresh = { ConnectionService.refreshNow(scope) },
        state = rememberPullToRefreshState(),
        modifier = modifier.fillMaxSize(),
    ) {
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                contentPadding = PaddingValues(
                    start = ScreenPadding,
                    end = ScreenPadding,
                    top = 12.dp,
                    bottom = GlassNavBarContentPadding,
                ),
                verticalArrangement = Arrangement.spacedBy(BlockSpacing),
            ) {
                item { HomeHeader(state, Modifier.fillMaxWidth()) }

                val list = if (sessions.isEmpty() && session != null) listOfNotNull(session) else sessions
                items(list, key = { it.sessionId }) { entry ->
                    SessionCard(activity, entry, Modifier.fillMaxWidth()) { onOpenSession(entry.sessionId) }
                }
                if (list.isEmpty()) {
                    item { EmptySessions(Modifier.fillMaxWidth()) }
                }
            }
            ExtendedFloatingActionButton(
                onClick = {
                    if (!creating && state is RingingConnection.State.Connected) createSession()
                },
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = ScreenPadding, bottom = GlassNavBarContentPadding - 24.dp),
            ) { Text(if (creating) "创建中…" else "新建会话") }
        }
    }
}

/** 顶部：应用名大标题 + 连接状态点（endpoint 等细节移到设置屏）。 */
@Composable
private fun HomeHeader(state: RingingConnection.State, modifier: Modifier = Modifier) {
    val label = when (state) {
        is RingingConnection.State.Connected -> "已连接电脑端"
        is RingingConnection.State.Connecting -> "正在连接…"
        is RingingConnection.State.Failed -> "连接失败（自动重试中）"
        RingingConnection.State.Disconnected -> "未连接"
    }
    val color = when (state) {
        is RingingConnection.State.Connected -> MaterialTheme.colorScheme.primary
        is RingingConnection.State.Connecting -> MaterialTheme.colorScheme.tertiary
        is RingingConnection.State.Failed -> MaterialTheme.colorScheme.error
        RingingConnection.State.Disconnected -> MaterialTheme.colorScheme.outline
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("QAQ Harness", style = MaterialTheme.typography.headlineLarge)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier
                    .size(StatusDotSize)
                    .clip(CircleShape)
                    .background(color)
            )
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun EmptySessions(modifier: Modifier = Modifier) {
    Text(
        "等待发现活跃会话…\n下拉刷新，或在电脑端开启一个会话",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(vertical = 48.dp),
    )
}

/**
 * 会话卡片（M3 Expressive 大圆角 tonal 卡）：摘要做主标题，ULID 降为元信息；
 * 状态 = 彩色点 + 文案（运行中有呼吸动画），不再用描边胶囊。
 */
@Composable
private fun SessionCard(
    activity: String?,
    session: com.qaqh.harness.data.ringing.model.SessionListEntry,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    // 状态来源优先级：会话执行中（新旧 daemon 兼容判定）> control activity > status 词表等待态
    val running = session.isWorking || activity == "running"
    val displayState = when {
        running -> "running"
        session.runStatus.startsWith("waiting_") -> session.runStatus
        session.runStatus == "error" -> "error"
        else -> activity
    }
    val ap = presentActivity(displayState)
    Card(
        modifier = modifier.clickable(onClick = onClick, role = Role.Button),
        shape = RoundedCornerShape(CardCorner),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            Modifier.padding(CardInnerPadding),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                RunningDot(running, ap)
                Text(
                    ap.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (running) ap.color else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "· ${session.model ?: "未知模型"}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            // 标题契约：title（新 daemon）→ last_summary（旧 daemon）→ 兜底
            val summary = session.displayTitle
            Text(
                summary ?: "未命名会话",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.alpha(if (summary == null) 0.6f else 1f),
            )
            Text(
                "${session.sessionId.take(12)}… · ${session.turnCount} 轮",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 运行中 = 呼吸动画的状态点；否则静态点。 */
@Composable
private fun RunningDot(running: Boolean, ap: ActivityPresentation) {
    if (running) {
        val transition = rememberInfiniteTransition(label = "runningDot")
        val alpha by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
            label = "runningDotAlpha",
        )
        Box(
            Modifier
                .size(StatusDotSize)
                .alpha(alpha)
                .clip(CircleShape)
                .background(ap.color)
        )
    } else {
        Box(
            Modifier
                .size(StatusDotSize)
                .clip(CircleShape)
                .background(ap.color)
        )
    }
}

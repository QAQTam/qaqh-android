package com.qaqh.harness.ui.home

import androidx.compose.foundation.background
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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qaqh.harness.connection.ConnectionService
import com.qaqh.harness.connection.PendingApproval
import com.qaqh.harness.data.pairing.StoredCredentials
import com.qaqh.harness.data.ringing.RingingConnection
import com.qaqh.harness.ui.approvals.authenticateForApproval
import kotlinx.coroutines.launch

// ---- 布局常量：全屏统一，禁止散落魔数 ----
private val ScreenPadding = 16.dp
private val BlockSpacing = 16.dp
private val CardInnerPadding = 16.dp
private val CardItemSpacing = 8.dp
private val StatusDotSize = 10.dp

/** 连接状态 → 状态点颜色 / 文案。 */
private data class ConnectionPresentation(val color: androidx.compose.ui.graphics.Color, val label: String)

@Composable
private fun present(state: RingingConnection.State): ConnectionPresentation =
    when (state) {
        is RingingConnection.State.Connected ->
            ConnectionPresentation(MaterialTheme.colorScheme.primary, "已连接电脑端")

        is RingingConnection.State.Connecting ->
            ConnectionPresentation(MaterialTheme.colorScheme.tertiary, "正在连接…")

        is RingingConnection.State.Failed ->
            ConnectionPresentation(MaterialTheme.colorScheme.error, "连接失败（自动重试中）")

        RingingConnection.State.Disconnected ->
            ConnectionPresentation(MaterialTheme.colorScheme.outline, "未连接")
    }

/** 会话活动状态 → 徽标文案。 */
private fun activityLabel(state: String?): String = when (state) {
    null -> "无活动"
    "running" -> "工作中"
    "idle" -> "空闲"
    "interrupted" -> "已中断"
    else -> state
}

@Composable
fun HomeScreen(
    credentials: StoredCredentials,
    onUnpair: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by ConnectionService.connectionState.collectAsState()
    val activity by ConnectionService.activity.collectAsState()
    val approvals by ConnectionService.pendingApprovals.collectAsState()
    val session by ConnectionService.sessionInfo.collectAsState()
    val respondError by ConnectionService.lastRespondError.collectAsState()

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(BlockSpacing),
    ) {
        item { ConnectionHeader(credentials, state, Modifier.fillMaxWidth()) }
        item { SessionCard(activity, session, Modifier.fillMaxWidth()) }
        if (approvals.isNotEmpty()) {
            item { Text("待审批", style = MaterialTheme.typography.titleMedium) }
            items(approvals, key = { it.interactionId }) { approval ->
                ApprovalCard(approval, Modifier.fillMaxWidth())
            }
        }
        item { UnpairButton(onUnpair, Modifier.fillMaxWidth()) }
    }
}

/** 顶部：应用名 + 状态点 + 状态与地址（单行省略）。 */
@Composable
private fun ConnectionHeader(
    credentials: StoredCredentials,
    state: RingingConnection.State,
    modifier: Modifier = Modifier,
) {
    val p = present(state)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(CardItemSpacing)) {
        Text("QAQ-Harness", style = MaterialTheme.typography.headlineMedium)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CardItemSpacing),
        ) {
            Box(
                Modifier
                    .size(StatusDotSize)
                    .clip(CircleShape)
                    .background(p.color)
            )
            Text(
                p.label,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            credentials.baseUrl,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
        )
    }
}

/** 会话卡片：标题行（含活动徽标）+ 摘要 + 元信息，全部单行/两行省略。 */
@Composable
private fun SessionCard(
    activity: String?,
    session: com.qaqh.harness.data.ringing.model.SessionListEntry?,
    modifier: Modifier = Modifier,
) {
    Card(modifier) {
        Column(
            Modifier.padding(CardInnerPadding),
            verticalArrangement = Arrangement.spacedBy(CardItemSpacing),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(CardItemSpacing),
            ) {
                Text("会话", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                AssistChip(onClick = {}, label = { Text(activityLabel(activity)) })
            }
            if (session == null) {
                Text("等待发现活跃会话…", style = MaterialTheme.typography.bodySmall)
            } else {
                Text(
                    session.lastSummary?.takeIf { it.isNotBlank() } ?: session.sessionId,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(
                        session.model ?: "-",
                        "${session.turnCount} 轮",
                        if (session.running) "running" else null,
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 待审批卡片：按 kind 显示标题与按钮组（ask 只有"关闭"），操作前生物认证把关。 */
@Composable
private fun ApprovalCard(approval: PendingApproval, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }

    fun respondWithGate(approved: Boolean) {
        val verb = when (approval.kind) {
            "plan" -> if (approved) "批准计划" else "拒绝计划"
            "ask" -> "关闭提问"
            else -> if (approved) "同意" else "拒绝"
        }
        val fragmentActivity = context as? androidx.fragment.app.FragmentActivity
        fun proceed() {
            scope.launch {
                ConnectionService.respondApproval(approval, approved)
                busy = false
            }
        }
        if (fragmentActivity == null) {
            proceed()
            return
        }
        fragmentActivity.authenticateForApproval(
            title = "确认$verb",
            subtitle = approval.summary.take(60),
            onAllowed = ::proceed,
            onDenied = { busy = false },
        )
    }

    Card(modifier) {
        Column(
            Modifier.padding(CardInnerPadding),
            verticalArrangement = Arrangement.spacedBy(CardItemSpacing),
        ) {
            Text(
                when (approval.kind) {
                    "plan" -> "计划确认"
                    "ask" -> "提问"
                    else -> "工具调用请求"
                },
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                approval.summary,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                approval.callId ?: approval.interactionId,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(CardItemSpacing)) {
                when (approval.kind) {
                    "ask" -> {
                        OutlinedButton(
                            enabled = !busy,
                            onClick = { busy = true; respondWithGate(false) },
                            modifier = Modifier.weight(1f),
                        ) { Text("关闭（中止回合）") }
                    }
                    else -> {
                        Button(
                            enabled = !busy,
                            onClick = { busy = true; respondWithGate(true) },
                            modifier = Modifier.weight(1f),
                        ) { Text("同意") }
                        OutlinedButton(
                            enabled = !busy,
                            onClick = { busy = true; respondWithGate(false) },
                            modifier = Modifier.weight(1f),
                        ) { Text("拒绝") }
                    }
                }
            }
        }
    }
}

@Composable
private fun UnpairButton(onUnpair: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(onClick = onUnpair, modifier = modifier) { Text("解除配对") }
}

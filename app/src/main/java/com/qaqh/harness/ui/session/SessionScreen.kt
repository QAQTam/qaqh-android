package com.qaqh.harness.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import com.qaqh.harness.data.ringing.RingingConnection
import com.qaqh.harness.data.ringing.SessionTimeline
import com.qaqh.harness.data.ringing.model.TimelineBlock
import com.qaqh.harness.data.ringing.model.TimelineTool
import com.qaqh.harness.data.ringing.model.TimelineTurn
import com.qaqh.harness.ui.approvals.authenticateForApproval
import kotlinx.coroutines.launch

// ---- 布局常量（与 HomeScreen 同一尺度体系）----
private val ScreenPadding = 16.dp
private val BlockSpacing = 8.dp
private val CardInnerPadding = 12.dp
private val BubbleRadius = 12.dp

@Composable
fun SessionScreen(
    sessionId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val connection = ConnectionService.activeConnection()

    if (connection == null) {
        Column(modifier.fillMaxSize().padding(ScreenPadding)) {
            Text("未连接", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = onBack, modifier = Modifier.padding(top = BlockSpacing)) { Text("返回") }
        }
        return
    }

    val timeline = remember(sessionId) { SessionTimeline(connection, sessionId, scope) }
    val turns by timeline.turns.collectAsState()
    val status by timeline.status.collectAsState()
    val approvals by ConnectionService.pendingApprovals.collectAsState()
    val respondError by ConnectionService.lastRespondError.collectAsState()
    val sendError by timeline.sendError.collectAsState()
    val activity by ConnectionService.activity.collectAsState()
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }

    // 进入会话：服务切流 + attach；退出时停 SSE
    LaunchedEffect(sessionId) { ConnectionService.selectSession(sessionId) }
    DisposableEffect(sessionId) {
        timeline.start(onError = {})
        onDispose { timeline.stop() }
    }
    // 新消息自动滚底
    LaunchedEffect(turns.size, turns.lastOrNull()?.rounds?.lastOrNull()?.blocks?.size) {
        if (turns.isNotEmpty()) runCatching { listState.animateScrollToItem((turns.size - 1).coerceAtLeast(0)) }
    }

    // 底部 inset 取 nav bar 与键盘的较大者，避免键盘弹出时输入框悬空一段导航栏高度
    Column(modifier.fillMaxSize().windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))) {
        // 顶栏
        Row(
            Modifier.fillMaxWidth().padding(ScreenPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BlockSpacing),
        ) {
            IconButton(onClick = onBack) { Text("←", style = MaterialTheme.typography.titleLarge) }
            Column(Modifier.weight(1f)) {
                Text(
                    "会话 ${sessionId.take(8)}…",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val activityText = when (activity) {
                    "running" -> "工作中"
                    "idle" -> "空闲"
                    "interrupted" -> "已中断"
                    else -> activity ?: ""
                }
                Text(
                    activityText,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        // 聊天区
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = ScreenPadding, vertical = BlockSpacing),
            verticalArrangement = Arrangement.spacedBy(BlockSpacing),
        ) {
            items(turns, key = { it.turnId }) { turn -> TurnItem(turn) }
            when (status) {
                com.qaqh.harness.data.ringing.SessionTimeline.Status.Loading ->
                    item { Text("加载中…", style = MaterialTheme.typography.bodySmall) }
                is com.qaqh.harness.data.ringing.SessionTimeline.Status.Error ->
                    item {
                        Text(
                            (status as com.qaqh.harness.data.ringing.SessionTimeline.Status.Error).message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                else -> Unit
            }
        }

        sendError?.let { err ->
            Text(
                err,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = ScreenPadding),
            )
        }
        respondError?.let { err ->
            Text(
                err,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = ScreenPadding),
            )
        }

        // 待审批（当前会话）
        approvals.forEach { approval ->
            ApprovalCard(approval, Modifier.padding(horizontal = ScreenPadding))
        }

        // 输入行
        Row(
            Modifier.fillMaxWidth().padding(ScreenPadding),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(BlockSpacing),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("发送消息…") },
                modifier = Modifier.weight(1f),
                maxLines = 4,
            )
            Button(
                enabled = !sending && input.isNotBlank(),
                onClick = {
                    sending = true
                    val text = input.trim()
                    input = ""
                    scope.launch {
                        runCatching { timeline.send(text) }
                        sending = false
                    }
                },
            ) { Text("发送") }
        }
    }
}

// ---------- 消息渲染 ----------

@Composable
private fun TurnItem(turn: TimelineTurn) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        turn.userText?.takeIf { it.isNotBlank() }?.let { userText ->
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                Text(
                    userText,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(BubbleRadius))
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(horizontal = CardInnerPadding, vertical = 8.dp),
                )
            }
        }
        turn.rounds.forEach { round ->
            round.blocks.forEach { block -> BlockItem(block) }
        }
        if (turn.state == "running") {
            Text("…", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 4.dp))
        }
    }
}

@Composable
private fun BlockItem(block: TimelineBlock) {
    when (block.kind) {
        "reasoning" -> ReasoningItem(block)
        "text" -> if (!block.text.isNullOrBlank()) {
            Text(
                block.text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            )
        }

        "tool" -> block.tool?.let { ToolCard(it) }
        "notice" -> Text(
            block.text ?: "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ReasoningItem(block: TimelineBlock) {
    var expanded by remember(block.blockId) { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
    ) {
        Column(Modifier.padding(CardInnerPadding)) {
            Text(
                "思考过程" + if (block.state == "open") "（进行中）" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (expanded) {
                Text(
                    block.text ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/** 工具调用卡片：状态点 + 名称 + 摘要；点击展开参数/输出。 */
@Composable
private fun ToolCard(tool: TimelineTool) {
    var expanded by remember(tool.toolCallId) { mutableStateOf(false) }
    val (color, stateLabel) = toolPresentation(tool.state)
    Card(modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
        Column(Modifier.padding(CardInnerPadding), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(BlockSpacing)) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(color))
                Text(tool.name ?: "tool", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                Text(stateLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            tool.summary?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, maxLines = if (expanded) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
            }
            if (expanded) {
                tool.argsJson?.takeIf { it.isNotBlank() }?.let {
                    Text("参数：$it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                tool.output?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 20,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(8.dp),
                    )
                }
                tool.exitCode?.let { Text("exit code: $it", style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
}

private data class ToolPresentation(val color: androidx.compose.ui.graphics.Color, val label: String)

@Composable
private fun toolPresentation(state: String?): ToolPresentation = when (state) {
    "succeeded" -> ToolPresentation(MaterialTheme.colorScheme.primary, "完成")
    "failed" -> ToolPresentation(MaterialTheme.colorScheme.error, "失败")
    "cancelled" -> ToolPresentation(MaterialTheme.colorScheme.outline, "已取消")
    "running", "prepared" -> ToolPresentation(MaterialTheme.colorScheme.tertiary, "运行中")
    else -> ToolPresentation(MaterialTheme.colorScheme.outline, state ?: "-")
}

// ---------- 审批卡片（permission / ask / plan） ----------

@Composable
fun ApprovalCard(approval: PendingApproval, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember(approval.interactionId) { mutableStateOf(false) }

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
        Column(Modifier.padding(CardInnerPadding), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(BlockSpacing)) {
                when (approval.kind) {
                    "ask" -> OutlinedButton(
                        enabled = !busy,
                        onClick = { busy = true; respondWithGate(false) },
                        modifier = Modifier.weight(1f),
                    ) { Text("关闭（中止回合）") }

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

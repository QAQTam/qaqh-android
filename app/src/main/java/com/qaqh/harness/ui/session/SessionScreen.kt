package com.qaqh.harness.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
    var input by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }

    // 进入会话：服务切流 + attach；退出时停 SSE
    LaunchedEffect(sessionId) { ConnectionService.selectSession(sessionId) }
    DisposableEffect(sessionId) {
        timeline.start(onError = {})
        onDispose { timeline.stop() }
    }

    // ---- 自动追底：交互驱动的跟随开关 ----
    // followBottom 只被两种事件改写：用户拖动（离开）/ 拖回底部或自己发消息（恢复）。
    // 不用 layoutInfo 推断"是否贴底"——contentPadding/长 item 残余偏移会让判定抖动失效。
    val listState = rememberLazyListState()
    var followBottom by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is androidx.compose.foundation.interaction.DragInteraction.Start -> followBottom = false
                is androidx.compose.foundation.interaction.DragInteraction.Stop,
                is androidx.compose.foundation.interaction.DragInteraction.Cancel,
                -> if (!listState.canScrollForward) followBottom = true
            }
        }
    }
    var didInitialJump by remember { mutableStateOf(false) }
    // 打开聊天屏：无条件跳到底部（历史按最旧在上加载，起点必须是最新的）
    LaunchedEffect(turns.isNotEmpty()) {
        if (turns.isNotEmpty() && !didInitialJump) {
            didInitialJump = true
            pinToBottom(listState)
        }
    }
    // 新回合出现：自己发的消息（带用户文本）无条件回底；其余新内容跟随开关打开才追
    LaunchedEffect(turns.size) {
        if (!didInitialJump) return@LaunchedEffect
        val lastTurn = turns.lastOrNull() ?: return@LaunchedEffect
        if (lastTurn.userText != null) followBottom = true
        if (followBottom) pinToBottom(listState)
    }
    // 流式文本增长：跟随打开时把底边钉在视口底（无动画）。
    // 80ms 节流：每条 delta 都滚动会强制长 item 反复 measure/layout（JankStats 实测
    // 32-57ms 的 jank 与此相关）；80ms 内多次 delta 合并成一次滚动，残余偏移不足一行。
    var lastFollowMs by remember { mutableLongStateOf(0L) }
    val lastBlockSignature = turns.lastOrNull()?.rounds?.lastOrNull()?.blocks
        ?.joinToString("/") { "${it.blockId}:${it.text?.length ?: 0}" }
    LaunchedEffect(lastBlockSignature) {
        if (didInitialJump && followBottom) {
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastFollowMs >= 80) {
                lastFollowMs = now
                pinToBottom(listState)
            }
        }
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

/** 把最后一项的底边钉在视口底：滚到最后一项后正向滚一个大量程——LazyList 会在真正底边 clamp，
 *  天然处理"item 高于视口"与 contentPadding 残余偏移。 */
private suspend fun pinToBottom(listState: androidx.compose.foundation.lazy.LazyListState) {
    runCatching {
        val last = listState.layoutInfo.totalItemsCount - 1
        if (last >= 0) listState.scrollToItem(last)
        listState.scrollBy(10_000f)
    }
}

/**
 * debug 重组探针：统计自身每 2s 被重组的次数并打日志。
 * 证据用法：流式期间只有"正在生成的那个 turn"计数在涨，其余 turn 恒为 0，
 * 即没有整棵树重渲染（LazyColumn 虚拟化 + item 相等性跳过）。
 */
@Composable
private fun DebugRecomposeCounter(tag: String) {
    if (!com.qaqh.harness.BuildConfig.DEBUG) return
    val count = remember { mutableStateOf(0) }
    androidx.compose.runtime.SideEffect { count.value++ }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(2_000)
            if (count.value > 0) {
                android.util.Log.d("RecomposeProbe", "$tag recomposed x${count.value} in 2s")
                count.value = 0
            }
        }
    }
}

@Composable
private fun TurnItem(turn: TimelineTurn) {
    // debug 质量探针：未变化的 turn 不应重组（计数持续为 0 即"没有整棵树重渲染"的证据）
    if (com.qaqh.harness.BuildConfig.DEBUG) DebugRecomposeCounter("turn=${turn.turnId.take(8)}")
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
    // 折叠态默认只显示最新 3 行（流式时文本增长，尾部 3 行跟着滚动）；展开看全文。
    // remember(block.text)：只为正在流式的那个块每帧重算，其他块零成本。
    val tailLines = remember(block.text) {
        block.text?.trim()?.lineSequence()?.filter { it.isNotBlank() }?.toList()?.takeLast(3).orEmpty()
    }
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
            } else {
                Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    tailLines.forEach { line ->
                        Text(
                            line,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 工具调用卡片（数据清洗对齐 TUI）：标题 = 动词 + typed header 正文（单行裁剪），
 * 折叠态预览输出尾部 3 行（mono），展开看全文/原始参数；失败头部带 exit code，理由只在正文。
 */
@Composable
private fun ToolCard(tool: TimelineTool) {
    var expanded by remember(tool.toolCallId) { mutableStateOf(false) }
    var showRawArgs by remember(tool.toolCallId) { mutableStateOf(false) }
    val running = tool.state == "running" || tool.state == "prepared"
    val head = remember(tool) { ToolFormatter.header(tool) }
    val dotColor = when {
        head.failed -> MaterialTheme.colorScheme.error
        tool.state == "succeeded" -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outline
    }
    val stateText = when (tool.state) {
        "succeeded" -> "完成"
        "failed" -> "失败"
        "cancelled" -> "已取消"
        "backgrounded" -> "后台"
        else -> tool.state ?: ""
    }
    val body = remember(tool) { ToolFormatter.body(tool) }
    val preview = remember(body) { body?.let { ToolFormatter.previewTail(it) } }
    val elapsed = remember(tool) { ToolFormatter.elapsedLabel(tool) }
    val clipboard = LocalContext.current.getSystemService(android.content.ClipboardManager::class.java)

    Card(modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
        Column(Modifier.padding(CardInnerPadding), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // 头部：状态 + 动词 + 单行正文 + 时长/状态
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(BlockSpacing)) {
                if (running) {
                    CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
                } else {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(dotColor))
                }
                Text(
                    buildString {
                        append(head.verb)
                        head.rest?.takeIf { it.isNotBlank() }?.let { append(' '); append(it) }
                        if (head.failed && head.exitCode != null && head.exitCode != 0) append(" (exit ${head.exitCode})")
                    },
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                elapsed?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!running) {
                    Text(
                        stateText,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (head.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // 折叠预览：输出尾部 3 行；展开：全文
            if (expanded) {
                body?.let { text ->
                    Text(
                        text,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                        maxLines = 200,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(8.dp),
                    )
                    TextButton(onClick = {
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("output", text))
                    }) { Text("复制输出") }
                }
                tool.failure?.message?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                // 原始参数默认隐藏（对齐 TUI：args_json 永不进标题，按需查看）
                TextButton(onClick = { showRawArgs = !showRawArgs }) {
                    Text(if (showRawArgs) "隐藏原始参数" else "原始参数", style = MaterialTheme.typography.labelSmall)
                }
                if (showRawArgs) {
                    tool.argsJson?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 40,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                tool.progressTruncated.takeIf { it }?.let {
                    Text("… 输出已截断", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else if (preview != null) {
                val (lines, hidden) = preview
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    lines.forEach { line ->
                        Text(
                            line,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (hidden > 0) {
                    Text(
                        "… +$hidden 行（点击展开）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
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

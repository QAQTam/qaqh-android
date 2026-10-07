@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.qaqh.harness.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Build
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
    val todos by timeline.todos.collectAsState()
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

    // 待办通知已改为 ConnectionService 全局跟踪器驱动（离开聊天屏仍更新/上胶囊），
    // 聊天屏内只保留一行入口 chip

    // 底部 inset 取 nav bar 与键盘的较大者；顶部让出状态栏（聊天屏全屏覆盖 tab 壳，无 Scaffold 提供 padding）
    Column(
        modifier
            .fillMaxSize()
            .windowInsetsPadding(
                WindowInsets.ime
                    .union(WindowInsets.navigationBars)
                    .union(WindowInsets.statusBars),
            ),
    ) {
        // 顶栏：返回 + 会话摘要/ULID + 模型 chip + 状态点
        val listEntry by ConnectionService.sessions.collectAsState()
        val sessionInfo by ConnectionService.sessionInfo.collectAsState()
        val entry = remember(listEntry, sessionInfo, sessionId) {
            listEntry.find { it.sessionId == sessionId } ?: sessionInfo?.takeIf { it.sessionId == sessionId }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = ScreenPadding, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BlockSpacing),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Column(Modifier.weight(1f)) {
                Text(
                    entry?.displayTitle?.takeIf { it.isNotBlank() } ?: "会话 ${sessionId.take(8)}…",
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
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    entry?.model?.let { model ->
                        Text(
                            model,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text("·", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        activityText,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (activity == "running") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // 待办：一行进度 chip（胶囊通知做常驻 surface，聊天区不再嵌大面板）；点开看完整清单
        todos?.takeIf { !it.allDone }?.let { todo ->
            var showTodoSheet by remember { mutableStateOf(false) }
            TodoChip(todo, Modifier.padding(horizontal = ScreenPadding)) { showTodoSheet = true }
            if (showTodoSheet) {
                ModalBottomSheet(onDismissRequest = { showTodoSheet = false }) {
                    TodoPanel(todo, Modifier.padding(horizontal = ScreenPadding).padding(bottom = 24.dp))
                }
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

        // 输入行：胶囊输入框 + 圆形发送钮（与玻璃导航条同一圆角语言）
        Row(
            Modifier.fillMaxWidth().padding(horizontal = ScreenPadding, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(BlockSpacing),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("发送消息…") },
                modifier = Modifier.weight(1f),
                maxLines = 4,
                shape = RoundedCornerShape(28.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                ),
            )
            FilledIconButton(
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
                shape = CircleShape,
                modifier = Modifier.size(52.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
            }
        }
    }
}

/** 流式期只渲染尾部：封顶单次重排版成本（sealed 后由 Markdown 渲染全文）。 */
private fun streamTail(text: String, max: Int = 1_200): String =
    if (text.length <= max + 300) text else "…" + text.takeLast(max)

/**
 * 待办入口 chip：单行进度摘要（N/M + 进行中条目），点击弹出完整清单（TodoPanel）。
 * 常驻进度呈现已移交通知胶囊（Live Updates），聊天区只留轻量入口。
 */
@Composable
private fun TodoChip(
    todo: com.qaqh.harness.data.ringing.model.TodoState,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val current = todo.inProgress
    AssistChip(
        onClick = onClick,
        modifier = modifier,
        label = {
            Text(
                buildString {
                    append("待办 ${todo.completed}/${todo.total}")
                    current?.let { append(" · ").append(it.title.ifBlank { it.id }) }
                },
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingIcon = {
            Text("☑", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        },
    )
}

/**
 * 待办清单面板：进行中→待办→已完成排序，折叠预览 3 行；全部完成时整个隐藏（调用方保证）。
 */
@Composable
private fun TodoPanel(todo: com.qaqh.harness.data.ringing.model.TodoState, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val items = todo.ordered()
    val visible = if (expanded) items else items.take(3)
    Card(modifier) {
        Column(Modifier.padding(CardInnerPadding), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "待办 ${todo.completed}/${todo.total}",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                if (items.size > 3) {
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(if (expanded) "收起" else "展开 ${items.size} 项", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            visible.forEach { item ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(BlockSpacing),
                ) {
                    val (marker, color) = when (item.status) {
                        com.qaqh.harness.data.ringing.model.TodoStatus.COMPLETED ->
                            "✓" to MaterialTheme.colorScheme.primary
                        com.qaqh.harness.data.ringing.model.TodoStatus.IN_PROGRESS ->
                            "▸" to MaterialTheme.colorScheme.tertiary
                        com.qaqh.harness.data.ringing.model.TodoStatus.PENDING ->
                            "○" to MaterialTheme.colorScheme.outline
                    }
                    Text(marker, style = MaterialTheme.typography.bodyMedium, color = color)
                    Text(
                        item.title.ifBlank { item.id },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (item.status == com.qaqh.harness.data.ringing.model.TodoStatus.COMPLETED) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
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
    val blocks = remember(turn) { turn.rounds.flatMap { it.blocks } }
    // 过程块（思考+工具）vs 内容块（正文/通知）——Qoder 式折叠的对象
    val processBlocks = blocks.filter { it.kind == "reasoning" || (it.kind == "tool" && it.tool != null) }

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

        if (turn.sealed) {
            // 回合完成：过程整体收成一行（Qoder assistantProcessFolding），流里只剩正文
            if (processBlocks.isNotEmpty()) {
                val show = remember { mutableStateOf(false) }
                ProcessChip(
                    label = "查看执行过程",
                    subtitle = processSummary(processBlocks),
                    stepCount = processBlocks.size,
                    onClick = { show.value = true },
                )
                if (show.value) {
                    ProcessSheet(processBlocks) { show.value = false }
                }
            }
        } else {
            // 回合进行中：过程全部按序展开（思考卡 3 行尾 + 工具卡带实时状态/预览），
            // sealed 后上面的分支自动接管、整体收缩为聚合芯片
            processBlocks.forEach { block ->
                if (block.kind == "reasoning") ReasoningItem(block) else block.tool?.let { ToolCard(it) }
            }
        }

        blocks.forEach { block ->
            if (block.kind == "text" || block.kind == "notice") BlockItem(block)
        }
        if (turn.state == "running") {
            Text("…", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 4.dp))
        }
    }
}

/** 过程聚合副标题：按家族分类计数（Qoder 分类摘要的简化版），如"编辑 2 · 运行 5 · 思考 1"。 */
private fun processSummary(blocks: List<TimelineBlock>): String? {
    val counts = LinkedHashMap<String, Int>()
    var failed = 0
    blocks.forEach { b ->
        val tool = b.tool
        if (tool == null) {
            counts["思考"] = (counts["思考"] ?: 0) + 1
            return@forEach
        }
        if (tool.state == "failed") failed++
        val family = toolFamilyLabel(tool.name)
        counts[family] = (counts[family] ?: 0) + 1
    }
    if (counts.isEmpty()) return null
    val parts = counts.entries.joinToString(" · ") { "${it.key} ${it.value}" }
    return if (failed > 0) "$parts · 失败 $failed" else parts
}

private fun toolFamilyLabel(name: String?): String = when (name?.lowercase()) {
    "exec", "shell", "bash", "run" -> "运行"
    "read" -> "读取"
    "grep", "search" -> "搜索"
    "glob", "ls", "find" -> "查找"
    "write" -> "写入"
    "edit", "apply_patch" -> "编辑"
    "todo_write", "todo_update", "todo_list" -> "计划"
    else -> "工具"
}

/** 过程聚合芯片：一行高度，图标 + 标签 + 步数 + 分类副标题（Qoder 折叠+计数方案）。 */
@Composable
private fun ProcessChip(
    label: String,
    subtitle: String?,
    stepCount: Int,
    failed: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BlockSpacing),
        ) {
            Icon(
                Icons.Filled.Build,
                contentDescription = null,
                tint = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "· $stepCount 步",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** 执行过程详情：底部弹层，完整工具卡/思考卡列表（细节不占聊天流）。 */
@Composable
private fun ProcessSheet(blocks: List<TimelineBlock>, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            contentPadding = PaddingValues(horizontal = ScreenPadding, vertical = BlockSpacing),
            verticalArrangement = Arrangement.spacedBy(BlockSpacing),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(blocks, key = { it.blockId }) { block ->
                if (block.kind == "reasoning") ReasoningItem(block) else block.tool?.let { ToolCard(it) }
            }
        }
    }
}

@Composable
private fun BlockItem(block: TimelineBlock) {
    when (block.kind) {
        "reasoning" -> ReasoningItem(block)
        "text" -> if (!block.text.isNullOrBlank()) {
            if (block.state == "sealed") {
                // 定稿：Markdown 渲染（参数不变时 Compose 跳过重组合，解析只在块定稿时发生一次）
                // 标题映射降到 title 级（库默认 h1=displayLarge，聊天流里一个标题占半屏）；
                // 正文统一 bodyMedium，与流式纯文本一致，避免 sealed 瞬间字号跳变
                com.mikepenz.markdown.m3.Markdown(
                    block.text,
                    typography = com.mikepenz.markdown.m3.markdownTypography(
                        h1 = MaterialTheme.typography.titleLarge,
                        h2 = MaterialTheme.typography.titleLarge,
                        h3 = MaterialTheme.typography.titleMedium,
                        h4 = MaterialTheme.typography.titleMedium,
                        h5 = MaterialTheme.typography.titleSmall,
                        h6 = MaterialTheme.typography.titleSmall,
                        text = MaterialTheme.typography.bodyMedium,
                        paragraph = MaterialTheme.typography.bodyMedium,
                        ordered = MaterialTheme.typography.bodyMedium,
                        bullet = MaterialTheme.typography.bodyMedium,
                        list = MaterialTheme.typography.bodyMedium,
                        table = MaterialTheme.typography.bodyMedium,
                    ),
                )
            } else {
                // 流式中：纯文本 + 只渲染尾部（webui 同款策略——增长的段落每帧全量重排版
                // 是 jank 主因，尾部截断把重排版成本封顶；sealed 后全文 Markdown）
                Text(
                    streamTail(block.text),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                )
            }
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
                // 文件变更类：统一 diff 着色（+/−/@）
                ToolFormatter.diff(tool)?.let { DiffView(it) }
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

/** 统一 diff 着色：+ 绿 / − 红 / @@ 界标灰（cap 200 行防爆量）。 */
@Composable
private fun DiffView(diff: String) {
    val add = MaterialTheme.colorScheme.primary
    val del = MaterialTheme.colorScheme.error
    val meta = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(8.dp),
    ) {
        diff.lines().take(200).forEach { line ->
            val color = when {
                line.startsWith("+++") || line.startsWith("---") -> meta
                line.startsWith("+") -> add
                line.startsWith("-") -> del
                line.startsWith("@@") -> meta
                else -> MaterialTheme.colorScheme.onSurface
            }
            Text(
                line,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
                ConnectionService.respondApproval(context, approval, approved)
                busy = false
            }
        }
        // 只有放行/拒绝类操作需要生物认证；ask 的"关闭（中止回合）"不是授权行为，
        // 不该要求指纹/密码
        if (approval.kind == "ask" || fragmentActivity == null) {
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

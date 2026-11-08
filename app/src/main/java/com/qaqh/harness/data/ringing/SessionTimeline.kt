package com.qaqh.harness.data.ringing

import com.qaqh.harness.data.core.ContentValue
import com.qaqh.harness.data.core.CoreConnection
import com.qaqh.harness.data.core.ToolCallView
import com.qaqh.harness.data.core.long
import com.qaqh.harness.data.core.obj
import com.qaqh.harness.data.core.str
import com.qaqh.harness.data.core.toContentValue
import com.qaqh.harness.data.core.CoreFrame
import com.qaqh.harness.data.core.SessionEvent
import com.qaqh.harness.data.core.SessionState
import com.qaqh.harness.data.core.TimelineEntryVm
import com.qaqh.harness.data.core.parseTimelineEntries
import com.qaqh.harness.data.core.toTimelineEntry
import com.qaqh.harness.data.ringing.model.CommandAck
import com.qaqh.harness.data.ringing.model.TimelineBlock
import com.qaqh.harness.data.ringing.model.TimelineRound
import com.qaqh.harness.data.ringing.model.TimelineTool
import com.qaqh.harness.data.ringing.model.TimelineTurn
import com.qaqh.harness.data.ringing.model.ToolDisplay
import com.qaqh.harness.data.ringing.model.ToolFailure
import com.qaqh.harness.data.ringing.model.ToolMetrics
import com.qaqh.harness.data.ringing.model.TodoState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 单会话聊天时间线状态器(v3):bootstrap 快照做底 + [com.qaqh.harness.data.core.CoreClient]
 * 的单流事件做增量。输出形状([TimelineTurn]/[TimelineBlock]/[TimelineTool])与 v2 时代
 * 逐字节一致——SessionScreen/ToolFormatter/测试零改动。
 *
 * 数据路径变化(v2 → v3):
 * - REST 分页(timeline/before_index)随 v3 退役:快照即全量历史,`loadOlder` 直达 EndReached;
 * - 事件词表换成 20 变体 SessionEvent(seq 是 epoch 内单调水位,快照 seq 为 exclusive 下界);
 * - 流订阅/重连/cursor 全在 Rust core,本类不再管传输。
 */
class SessionTimeline(
    private val connection: CoreConnection,
    val sessionId: String,
    private val scope: CoroutineScope,
) {
    private val _turns = MutableStateFlow<List<TimelineTurn>>(emptyList())
    val turns: StateFlow<List<TimelineTurn>> = _turns.asStateFlow()

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _todos = MutableStateFlow<TodoState?>(null)
    /** 当前待办清单(与实况通知同源;事件 ToolDeclared/Finished 摄取)。 */
    val todos: StateFlow<TodoState?> = _todos.asStateFlow()

    sealed interface Status {
        data object Idle : Status
        data object Loading : Status

        /** 历史已全部加载(v3 快照即全量,start 后即达)。 */
        data object EndReached : Status

        data class Error(val message: String) : Status
    }

    private var lastSeq = 0L
    private var mainJob: Job? = null

    // 可变工作集(copy-on-write 对外)
    private val turnsById = LinkedHashMap<String, TimelineTurn>()
    private val toolsByCall = HashMap<String, TimelineTool>()

    private val _sendError = MutableStateFlow<String?>(null)
    val sendError: StateFlow<String?> = _sendError.asStateFlow()

    /** 消费发送错误(Snackbar 展示后调用,避免同一条重复触发)。 */
    fun consumeSendError() {
        _sendError.value = null
    }

    /** 快照重建 + 订阅事件流(帧经本地 channel 缓冲,杜绝「快照与订阅之间丢帧」)。 */
    fun start(onError: (String) -> Unit) {
        if (mainJob != null) return
        mainJob = scope.launch {
            val framesChan = Channel<CoreFrame>(Channel.UNLIMITED)
            val pumper = launch {
                connection.client.frames.collect { frame ->
                    if (frame.sessionId == sessionId) framesChan.send(frame)
                }
            }
            try {
                _status.value = Status.Loading
                // 快照可能已在(CoreConnection 每次 Open/reset 刷新);等到首个本会话快照
                val snapshot = connection.latestSnapshot
                    .filterNotNull()
                    .filter { it.sessionId == sessionId }
                    .first()
                applyBootstrap(snapshot.bootstrap.state)
                _status.value = Status.EndReached
                // 快照 seq 是 exclusive 水位:跳过 seq < state.seq 的帧(已覆盖),处理 >= 的
                for (frame in framesChan) {
                    if (frame.seq >= lastSeq) applyEvent(frame)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _status.value = Status.Error(e.message ?: e.javaClass.simpleName)
                onError(e.message ?: "timeline 初始化失败")
            } finally {
                pumper.cancel()
            }
        }
    }

    fun stop() {
        mainJob?.cancel()
        mainJob = null
    }

    /** v3 无 REST 分页:快照即全量,翻页语义退化为「已到底」。 */
    fun loadOlder() {
        if (_status.value == Status.EndReached) return
        _status.value = Status.EndReached
    }

    suspend fun send(text: String): CommandAck? {
        _sendError.value = null
        return try {
            val ack = connection.sendUserMessage(text)
            if (ack.status != "accepted") {
                _sendError.value = when (ack.code) {
                    "not_driver" -> "命令未受理:另一客户端正在驾驶该会话(${ack.message ?: "driver seat occupied"})"
                    // view 档设备发会话命令会被 scope 门拒(需要 interact 档)
                    "insufficient_scope" -> "权限不足:当前设备档位无法执行该操作(需要 interact)"
                    "interaction_already_resolved" -> "该请求已被处理(电脑端已应答),无需重复操作"
                    else -> "命令未受理:${ack.code ?: ""} ${ack.message ?: ""}".trim()
                }
            }
            // v3:不再乐观拉页,用户输入经 timeline_appended(input) 事件回推
            ack
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _sendError.value = "发送失败:${e.message}"
            null
        }
    }

    // ---- 测试钩子(JVM 回归:直接灌事件走真实 reducer,不经网络)----

    internal fun applyEventForTest(event: SessionEvent, seq: Long = lastSeq) {
        lastSeq = seq
        applyEvent(event, seq)
    }

    internal fun applyBootstrapForTest(state: SessionState) = applyBootstrap(state)

    internal fun turnsForTest(): List<TimelineTurn> = _turns.value

    // ---------- 快照重建 ----------

    private fun applyBootstrap(state: SessionState) {
        lastSeq = state.seq
        turnsById.clear()
        toolsByCall.clear()
        state.tools.forEach { toolsByCall[it.callId] = it.toTool() }
        for (entry in parseTimelineEntries(state.timeline)) ingestEntry(entry)
        // 快照回合终态:outcome 有值 = 已定稿(sealTurn 推进块级 state,markdown 分支才会
        // 打开);无 outcome 的按 phase 恢复 running/非进行中。不消费 turns 的话,
        // ensureTurn 的硬编码会让重进的会话永远 "running" + 流式裸文本(v3 e2e 实测)。
        for (turnView in state.turns) {
            val existing = turnsById[turnView.turnId] ?: continue
            val stateLabel = turnView.stateLabel
            turnsById[turnView.turnId] = when {
                stateLabel != null -> sealTurn(existing, stateLabel)
                else -> existing.copy(
                    sealed = false,
                    state = if (turnView.phase == "running") "running" else "open",
                )
            }
        }
        // 快照内的待办:tools 里最后一个 todo 工具的参数即现状
        state.tools.lastOrNull { TodoState.isTodoTool(it.toolName) }?.let { tool ->
            _todos.value = TodoState.ingestTool(null, tool.toolName, tool.args?.toString())
        }
        publishTurns()
    }

    /** timeline 条目(快照或事件)→ 工作集;单一入口保证两种来源语义一致。 */
    private fun ingestEntry(entry: TimelineEntryVm) {
        when (entry) {
            is TimelineEntryVm.Input -> {
                val text = (entry.content as? ContentValue.Inline)?.text ?: return
                val turnId = entry.turnId
                if (turnId == null) {
                    // 无归属输入(系统注入):落为最新回合的 notice 块,不丢
                    newestUnsealedTurn()?.let { addBlock(it, entry.blockId, "notice", text) }
                    return
                }
                val turn = ensureTurn(turnId)
                turnsById[turnId] = turn.copy(userText = text)
            }

            is TimelineEntryVm.AssistantBlock -> {
                val text = (entry.content as? ContentValue.Inline)?.text
                val turn = ensureTurn(entry.turnId)
                addBlock(
                    turn,
                    entry.blockId,
                    if (entry.blockKind == "reasoning") "reasoning" else "text",
                    text,
                )
            }

            is TimelineEntryVm.ToolCall -> {
                val tool = toolsByCall.getOrPut(entry.callId) {
                    TimelineTool(toolCallId = entry.callId)
                }
                entry.turnId?.let { turnId ->
                    val turn = ensureTurn(turnId)
                    addBlock(turn, toolBlockId(entry.callId), "tool", null, tool)
                }
            }

            is TimelineEntryVm.ToolResult -> {
                val text = (entry.content as? ContentValue.Inline)?.text
                toolsByCall[entry.callId]?.let { toolsByCall[entry.callId] = it.copy(output = text ?: it.output) }
                replaceToolBlock(entry.callId)
            }

            is TimelineEntryVm.Compaction -> Unit // 压缩摘要:手机端暂不渲染
        }
    }

    // ---------- 事件 reducer ----------

    private fun applyEvent(frame: CoreFrame) {
        lastSeq = maxOf(lastSeq, frame.seq)
        applyEvent(frame.event, frame.seq)
    }

    private fun applyEvent(event: SessionEvent, seq: Long) {
        when (event) {
            is SessionEvent.TurnStarted -> {
                ensureTurn(event.turnId, seq)
                publishTurns()
            }

            is SessionEvent.TimelineAppended -> ingestEntry(entryOf(event.entry) ?: return)

            is SessionEvent.MessageDelta -> {
                // 替换语义(契约:同一 block_id 后续事件替换已展示内容)
                mutateBlock(event.turnId, event.blockId) { block ->
                    block.copy(
                        kind = if (event.blockKind == "reasoning") "reasoning" else "text",
                        text = event.text,
                    )
                }
            }

            is SessionEvent.ToolDeclared -> {
                val existing = toolsByCall[event.callId]
                toolsByCall[event.callId] = (existing ?: TimelineTool(toolCallId = event.callId)).copy(
                    name = event.toolName,
                    argsJson = event.args?.toString() ?: existing?.argsJson,
                    state = existing?.state?.takeIf { it != "prepared" } ?: "prepared",
                )
                event.turnId.let { turnId ->
                    val turn = ensureTurn(turnId)
                    addBlock(turn, toolBlockId(event.callId), "tool", null, toolsByCall[event.callId])
                }
                ingestTodo(event.toolName, toolsByCall[event.callId]?.argsJson)
                publishTurns()
            }

            is SessionEvent.ToolArgumentsDelta -> {
                // 参数生成进度:累计进 argsJson 供展示(替换语义的累计前缀)
                toolsByCall[event.callId]?.let {
                    toolsByCall[event.callId] = it.copy(argsJson = event.text, name = event.toolName)
                    replaceToolBlock(event.callId)
                    publishTurns()
                }
            }

            is SessionEvent.ToolStateChanged -> {
                toolsByCall[event.callId]?.let { tool ->
                    toolsByCall[event.callId] = tool.copy(state = stateLabelOf(event.state))
                    replaceToolBlock(event.callId)
                    publishTurns()
                }
            }

            is SessionEvent.ToolOutputDelta -> {
                toolsByCall[event.callId]?.let { tool ->
                    // 对齐 TUI:输出增量进 progress 尾窗(8K 截头保尾),不污染 output
                    val merged = tool.progress + event.text
                    val over = merged.length - MAX_PROGRESS_LEN
                    toolsByCall[event.callId] = tool.copy(
                        progress = if (over > 0) merged.substring(over) else merged,
                        progressTruncated = tool.progressTruncated || over > 0,
                    )
                    replaceToolBlock(event.callId)
                    publishTurns()
                }
            }

            is SessionEvent.ToolFinished -> {
                val outcome = event.outcome
                val error = outcome.obj("data")?.obj("error")
                val finished = toolsByCall.getOrPut(event.callId) { TimelineTool(toolCallId = event.callId) }
                toolsByCall[event.callId] = finished.copy(
                    state = outcomeLabelOf(outcome),
                    output = (event.output?.toContentValue() as? ContentValue.Inline)?.text,
                    exitCode = outcome.obj("data")?.long("exit_code")?.toInt(),
                    diff = null, // v3 只给结构化统计,不承载 diff 正文
                    progress = "",
                    display = event.metrics?.let { m ->
                        ToolDisplay(
                            metrics = ToolMetrics(
                                elapsedMs = (m.long("finished_at_ms") ?: 0) - (m.long("started_at_ms") ?: 0),
                                outputBytes = m.long("output_bytes"),
                                retryCount = m.long("retry_count")?.toInt(),
                            ),
                        )
                    },
                    failure = if (outcome.str("kind") == "failed") {
                        ToolFailure(
                            code = error?.str("code"),
                            message = error?.str("message"),
                        )
                    } else {
                        null
                    },
                )
                // todo 工具终态:以最终 args 刷新待办
                toolsByCall[event.callId]?.let { ingestTodo(it.name, it.argsJson) }
                replaceToolBlock(event.callId)
                publishTurns()
            }

            is SessionEvent.TurnEnded -> {
                val outcome = event.outcome
                val state = when (outcome.str("kind")) {
                    "interrupted" -> "interrupted"
                    else -> outcome.obj("data")?.str("terminal") ?: "completed"
                }
                turnsById[event.turnId]?.let {
                    turnsById[event.turnId] = sealTurn(it, state)
                }
                publishTurns()
            }

            is SessionEvent.TurnPhaseChanged -> Unit // 活动状态经 ActivityChanged 走服务投影
            is SessionEvent.ActivityChanged -> Unit
            is SessionEvent.InteractionRequested -> Unit
            is SessionEvent.InteractionResolved -> Unit
            is SessionEvent.SessionMetaChanged -> Unit
            is SessionEvent.DriverChanged -> Unit
            is SessionEvent.ExecSessionStarted -> Unit // 执行会话:手机端暂不渲染
            is SessionEvent.ExecSessionStateChanged -> Unit
            is SessionEvent.ExecSessionOutputDelta -> Unit
            is SessionEvent.ExecSessionFinished -> Unit
            is SessionEvent.SubagentUpdated -> Unit
        }
    }

    // ---------- 待办摄取 ----------

    private fun ingestTodo(toolName: String?, argsJson: String?) {
        if (!TodoState.isTodoTool(toolName)) return
        _todos.value = TodoState.ingestTool(_todos.value, toolName, argsJson) ?: _todos.value
    }

    // ---------- 工作集操作 ----------

    private fun entryOf(entry: JsonObject): TimelineEntryVm? = entry.toTimelineEntry()

    /** 契约 ToolCallView → UI 形状(state/outcome 词表映射同事件路径)。 */
    private fun ToolCallView.toTool(): TimelineTool {
        val finishedOutcome = finishedOutcome
        return TimelineTool(
            toolCallId = callId,
            name = toolName,
            argsJson = args?.toString(),
            state = stateLabelOf(state),
            output = (output?.toContentValue() as? ContentValue.Inline)?.text,
            exitCode = finishedOutcome?.obj("data")?.long("exit_code")?.toInt(),
            display = metrics?.let { m ->
                ToolDisplay(
                    metrics = ToolMetrics(
                        elapsedMs = (m.long("finished_at_ms") ?: 0) - (m.long("started_at_ms") ?: 0),
                        outputBytes = m.long("output_bytes"),
                        retryCount = m.long("retry_count")?.toInt(),
                    ),
                )
            },
            failure = if (state.str("kind") == "finished" &&
                finishedOutcome?.str("kind") == "failed"
            ) {
                val error = finishedOutcome.obj("data")?.obj("error")
                ToolFailure(code = error?.str("code"), message = error?.str("message"))
            } else {
                null
            },
        )
    }

    private fun ensureTurn(turnId: String, createdSeq: Long = 0): TimelineTurn {
        turnsById[turnId]?.let { return it }
        val turn = TimelineTurn(
            turnId = turnId,
            turnIndex = createdSeq,
            createdSeq = createdSeq,
            state = "running",
        )
        turnsById[turnId] = turn
        publishTurns()
        return turn
    }

    private fun toolBlockId(callId: String) = "tool:$callId"

    /**
     * 回合定稿:turn 置 sealed/state,块级 state 同步推进——BlockItem 的 markdown
     * 分支以 block.state == "sealed" 为闸门,不推块级就永远流式纯文本。
     */
    private fun sealTurn(turn: TimelineTurn, state: String): TimelineTurn = turn.copy(
        sealed = true,
        state = state,
        rounds = turn.rounds.map { round ->
            round.copy(blocks = round.blocks.map { block -> block.copy(state = "sealed") })
        },
    )

    private fun addBlock(
        turn: TimelineTurn,
        blockId: String,
        kind: String,
        text: String?,
        tool: TimelineTool? = null,
    ) {
        if (turn.rounds.any { it.blocks.any { b -> b.blockId == blockId } }) return
        val roundNum = (turn.rounds.maxOfOrNull { it.roundNum } ?: 0)
        val order = turn.rounds.sumOf { it.blocks.size }
        val block = TimelineBlock(
            blockId = blockId,
            blockOrder = order,
            kind = kind,
            state = "open",
            text = text,
            tool = tool,
        )
        val rounds = turn.rounds.ifEmpty { listOf(TimelineRound(roundNum = roundNum)) }
        val idx = rounds.indexOfFirst { it.roundNum == roundNum }.let { if (it >= 0) it else rounds.size }
        val round = rounds.getOrElse(idx) { TimelineRound(roundNum = roundNum) }
        val newRound = round.copy(blocks = round.blocks + block)
        val newRounds = buildList {
            addAll(rounds)
            if (idx < size) set(idx, newRound) else add(newRound)
        }
        turnsById[turn.turnId] = turn.copy(rounds = newRounds)
    }

    /** 把工具的现值回写进它所在的块(SSE 重放按 block_id 替换,不追加)。 */
    private fun replaceToolBlock(callId: String) {
        val tool = toolsByCall[callId] ?: return
        val blockId = toolBlockId(callId)
        turnsById.keys.toList().forEach { turnId ->
            val turn = turnsById[turnId] ?: return@forEach
            if (turn.rounds.none { it.blocks.any { b -> b.blockId == blockId } }) return@forEach
            turnsById[turnId] = turn.copy(
                rounds = turn.rounds.map { round ->
                    if (round.blocks.none { it.blockId == blockId }) round
                    else round.copy(
                        blocks = round.blocks.map { if (it.blockId == blockId) it.copy(tool = tool) else it },
                    )
                },
            )
        }
    }

    private fun mutateBlock(turnId: String, blockId: String, transform: (TimelineBlock) -> TimelineBlock) {
        val turn = ensureTurn(turnId)
        if (turn.rounds.none { it.blocks.any { b -> b.blockId == blockId } }) {
            // 流式块先于 turn/round 到位的兜底:直接建块(v3 顺序由 session owner 保证,防御性)
            addBlock(turn, blockId, "text", null)
        }
        val t = turnsById[turnId] ?: return
        turnsById[turnId] = t.copy(
            rounds = t.rounds.map { round ->
                if (round.blocks.none { it.blockId == blockId }) round
                else round.copy(
                    blocks = round.blocks.map { if (it.blockId == blockId) transform(it) else it },
                )
            },
        )
        publishTurns()
    }

    private fun newestUnsealedTurn(): TimelineTurn? =
        turnsById.values.lastOrNull { !it.sealed }

    private fun publishTurns() {
        _turns.value = turnsById.values.toList()
    }

    private companion object {
        /** 工具进度缓冲上限(对齐 TUI MAX_PROGRESS_LEN),超出截头保尾。 */
        const val MAX_PROGRESS_LEN = 8_192

        /** ToolCallState(kind 联合)→ 旧 UI 状态词表。 */
        fun stateLabelOf(state: JsonObject): String = when (state.str("kind")) {
            "declared" -> "prepared"
            "awaiting_interaction" -> "waiting"
            "running" -> "running"
            "cancelling" -> "cancelling"
            "finished" -> outcomeLabelOf(state.obj("data")?.obj("outcome") ?: JsonObject(emptyMap()))
            else -> "prepared"
        }

        /** ToolCallOutcome(kind 联合)→ 旧 UI 状态词表。 */
        fun outcomeLabelOf(outcome: JsonObject): String = when (outcome.str("kind")) {
            "completed" -> "succeeded"
            "yielded" -> "backgrounded"
            "failed" -> "failed"
            "cancelled" -> "cancelled"
            "interrupted" -> "failed"
            else -> "failed"
        }
    }
}

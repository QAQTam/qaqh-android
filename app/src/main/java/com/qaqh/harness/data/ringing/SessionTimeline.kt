package com.qaqh.harness.data.ringing

import com.qaqh.harness.data.ringing.model.CommandAck
import com.qaqh.harness.data.ringing.model.TimelineBlock
import com.qaqh.harness.data.ringing.model.TimelineEntryFrame
import com.qaqh.harness.data.ringing.model.TimelineRound
import com.qaqh.harness.data.ringing.model.TimelineTool
import com.qaqh.harness.data.ringing.model.TimelineTurn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 单会话聊天时间线状态器：timeline 分页做底 + timeline/events SSE 做实时增量，
 * reducer 语义对齐 webui session/reducer.ts。
 */
class SessionTimeline(
    private val connection: RingingConnection,
    val sessionId: String,
    private val scope: CoroutineScope,
) {
    private val _turns = MutableStateFlow<List<TimelineTurn>>(emptyList())
    val turns: StateFlow<List<TimelineTurn>> = _turns.asStateFlow()

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    /** 当前待办清单（来自 todo_write/todo_update 工具块的 args_json；null = 本会话无待办）。 */
    private val _todos = MutableStateFlow<com.qaqh.harness.data.ringing.model.TodoState?>(null)
    val todos: StateFlow<com.qaqh.harness.data.ringing.model.TodoState?> = _todos.asStateFlow()

    sealed interface Status {
        data object Idle : Status
        data object Loading : Status

        /** 历史已全部加载。 */
        data object EndReached : Status

        data class Error(val message: String) : Status
    }

    private var oldestIndex: Long? = null
    private var eventsJob: Job? = null
    private var endReached = false

    /**
     * 已消费的最大 timeline_seq（REST 页用 snapshot.watermark、SSE 帧用逐帧 seq 推进）。
     * 重订阅时作为 `Last-Event-ID: {epoch}:timeline:{seq}` 游标传给服务端——只补尾巴，
     * 不再全量回放（对齐 webui qaqh-client timeline.rs 的行为）。
     */
    private var lastSeq: Long? = null

    /** 首屏加载 + 订阅实时增量。 */
    fun start(onError: (String) -> Unit) {
        if (eventsJob != null) return
        eventsJob = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    _status.value = Status.Loading
                    loadPage()
                    // SSE 订阅循环：跟随租约（重连/续租失败轮换/切会话）自动重订阅。
                    // 不能只在 Connected 状态变化时订阅——续租失败路径的 connectOnce
                    // 不经过 Connecting，状态不变但租约已换，旧租约的 live 帧会被
                    // 服务端逐帧静默丢弃（sse.rs should_deliver_timeline_live）。
                    while (isActive) {
                        val sid = connection.leases.first()
                        // 游标 = 当前 epoch + 已消费水位：epoch 变了（daemon 重启）服务端会
                        // 自动退回全量回放（reducer 幂等），正常重连只补尾巴
                        val epoch = (connection.state.value as? RingingConnection.State.Connected)?.serverEpoch
                        val cursor = if (epoch != null && lastSeq != null) "$epoch:timeline:$lastSeq" else null
                        android.util.Log.i(
                            TAG,
                            "timeline SSE subscribe lease=${sid.take(8)} session=${sessionId.take(12)} " +
                                "cursor=${if (cursor != null) "seq=$lastSeq" else "none(full replay)"}",
                        )
                        try {
                            var frames = 0
                            coroutineScope {
                                // 空闲看门狗：服务端每 15s 发 keepalive 注释（OkHttp 监听器看不到），
                                // 正常流式期间帧间隔远小于此。长时间零帧说明流已半开/黑洞
                                // （网络切换、代理回收），带游标重订阅快速恢复。
                                var lastFrameAt = System.nanoTime()
                                val watchdog = launch {
                                    while (true) {
                                        delay(5_000)
                                        if (System.nanoTime() - lastFrameAt > IDLE_TIMEOUT_MS * 1_000_000) {
                                            throw StreamIdle
                                        }
                                    }
                                }
                                val leaseWaiter = launch {
                                    connection.leases.drop(1).first()
                                    throw LeaseRotated
                                }
                                try {
                                    connection.api.timelineEvents(sid, sessionId, cursor).collect { ev ->
                                        lastFrameAt = System.nanoTime()
                                        frames++
                                        if (frames == 1 || frames % 100 == 0) {
                                            android.util.Log.i(TAG, "timeline SSE frames=$frames")
                                        }
                                        when (ev) {
                                            is RingingApi.TimelineSseEvent.Entry -> applyFrame(ev.frame)
                                            RingingApi.TimelineSseEvent.Terminated ->
                                                android.util.Log.i(TAG, "timeline SSE terminated")
                                        }
                                    }
                                } finally {
                                    leaseWaiter.cancel()
                                    watchdog.cancel()
                                }
                            }
                            android.util.Log.i(TAG, "timeline SSE flow completed after $frames frames")
                        } catch (e: LeaseRotated) {
                            // 拿新租约立即重订阅
                            continue
                        } catch (e: StreamIdle) {
                            android.util.Log.w(TAG, "timeline SSE idle ${IDLE_TIMEOUT_MS}ms — resubscribe with cursor")
                            delay(1_000)
                        } catch (e: RingingApi.ApiException) {
                            android.util.Log.w(TAG, "timeline SSE ApiException ${e.httpStatus} ${e.code}")
                            if (e.httpStatus != 401) throw e
                        }
                        delay(1_000)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _status.value = Status.Error(e.message ?: e.javaClass.simpleName)
                onError(e.message ?: "timeline 初始化失败")
            }
        }
    }

    private object LeaseRotated : Exception("lease rotated")
    private object StreamIdle : Exception("stream idle")

    private companion object {
        const val TAG = "SessionTimeline"

        /** 无帧判定为死流的阈值；服务端 15s keepalive 只保管道，帧级空闲超过它即重订阅。 */
        const val IDLE_TIMEOUT_MS = 60_000L

        /** 工具进度缓冲上限（对齐 TUI MAX_PROGRESS_LEN），超出截头保尾。 */
        const val MAX_PROGRESS_LEN = 8_192
    }

    fun stop() {
        eventsJob?.cancel()
        eventsJob = null
    }

    // ---- 测试钩子（JVM 基准/回归：直接灌帧走真实 reducer，不经网络）----

    internal fun applyFrameForTest(frame: TimelineEntryFrame) = applyFrame(frame)

    internal fun turnsForTest(): List<TimelineTurn> = _turns.value

    /** 向上翻页（更早的历史；before_index 是排他游标，取当前最旧 turn_index）。 */
    fun loadOlder() {
        if (endReached || _status.value == Status.Loading) return
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    _status.value = Status.Loading
                    loadPage()
                }
            } catch (e: Exception) {
                _status.value = Status.Error(e.message ?: "分页失败")
            }
        }
    }

    private val _sendError = MutableStateFlow<String?>(null)
    val sendError: StateFlow<String?> = _sendError.asStateFlow()

    suspend fun send(text: String): CommandAck? {
        _sendError.value = null
        return try {
            val ack = withContext(Dispatchers.IO) { connection.sendUserMessage(text) }
            if (ack.status != "accepted") {
                _sendError.value = when (ack.code) {
                    "not_driver" -> "命令未受理：另一客户端正在驾驶该会话（${ack.message ?: "driver seat occupied"}）"
                    else -> "命令未受理：${ack.code ?: ""} ${ack.message ?: ""}".trim()
                }
            } else {
                // 乐观刷新：不等 SSE，立刻拉最新一页把新 turn 拉上来
                withContext(Dispatchers.IO) { refreshLatest() }
            }
            ack
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _sendError.value = "发送失败：${e.message}"
            null
        }
    }

    private suspend fun loadPage() {
        // 每次尝试都重新解析当前租约：切会话/重连会轮换 lease，旧租约必 401
        val resp = retryingOnLeaseRequired {
            val sid = awaitLease()
            connection.api.timeline(sid, sessionId, beforeIndex = oldestIndex, limit = 30)
        }
        _turns.value = (resp.snapshot.turns + _turns.value)
            .distinctBy { it.turnId }
            .sortedBy { it.turnIndex }
        lastSeq = maxOf(lastSeq ?: 0L, resp.snapshot.watermark)
        rebuildTodos()
        oldestIndex = _turns.value.firstOrNull()?.turnIndex
        if (!resp.hasMore) {
            endReached = true
            _status.value = Status.EndReached
        } else {
            _status.value = Status.Idle
        }
    }

    private suspend fun <T> retryingOnLeaseRequired(maxAttempts: Int = 4, block: suspend () -> T): T {
        var lastError: Exception? = null
        repeat(maxAttempts) { attempt ->
            try {
                return block()
            } catch (e: RingingApi.ApiException) {
                if (e.code == "lease_required" && attempt < maxAttempts - 1) {
                    lastError = e
                    delay(600L * (attempt + 1))
                } else {
                    throw e
                }
            }
        }
        throw lastError ?: IllegalStateException("unreachable")
    }

    /** 拉最新一页（不带 before_index）合并进列表，用于发送后的即时反馈。 */
    private suspend fun refreshLatest() {
        val resp = retryingOnLeaseRequired {
            val sid = awaitLease()
            connection.api.timeline(sid, sessionId, beforeIndex = null, limit = 30)
        }
        _turns.value = (resp.snapshot.turns + _turns.value)
            .distinctBy { it.turnId }
            .sortedBy { it.turnIndex }
        lastSeq = maxOf(lastSeq ?: 0L, resp.snapshot.watermark)
        rebuildTodos()
    }

    /** 分页/刷新后按时间序重放 todo 工具块，重建待办清单（冷进会话也能拿到当前状态）。 */
    private fun rebuildTodos() {
        var state: com.qaqh.harness.data.ringing.model.TodoState? = null
        _turns.value.sortedBy { it.turnIndex }.forEach { turn ->
            turn.rounds.forEach { round ->
                round.blocks.forEach { block ->
                    val tool = block.tool ?: return@forEach
                    if (!com.qaqh.harness.data.ringing.model.TodoState.isTodoTool(tool.name)) return@forEach
                    state = when (tool.name?.lowercase()) {
                        "todo_update" -> state?.let {
                            com.qaqh.harness.data.ringing.model.TodoState.parseUpdate(tool.argsJson, it)
                        } ?: state
                        else -> com.qaqh.harness.data.ringing.model.TodoState.parseWrite(tool.argsJson, state) ?: state
                    }
                }
            }
        }
        _todos.value = state
    }

    private suspend fun awaitLease(): String =
        connection.state.first { it is RingingConnection.State.Connected }
            .let { (it as RingingConnection.State.Connected).clientSessionId }

    // ---------- 事件 reducer ----------
    //
    // 帧形状对齐 qaqh-domain TimelineEvent（serde tag="type", snake_case）：
    // - block_opened 的块数据嵌在 event.block 下（TimelineBlock 完整值）；
    // - text_delta/tool_updated/block_sealed/block_checkpoint/tool_progress
    //   只带顶层 block_id；
    // - block_checkpoint：arg 为增量、text 为全量覆写（丢失 TextDelta 时重建用）；
    // - tool_progress：chunk 追加到工具输出。

    private fun applyFrame(frame: TimelineEntryFrame) {
        lastSeq = maxOf(lastSeq ?: 0L, frame.timelineSeq)
        val type = frame.type ?: return
        val data = frame.event as? JsonObject ?: return
        fun str(key: String): String? = (data[key] as? JsonPrimitive)?.content
        fun long(key: String): Long? = (data[key] as? JsonPrimitive)?.content?.toLongOrNull()
        fun nestedBlock(): JsonObject? = data["block"] as? JsonObject

        _turns.value = when (type) {
            "turn_opened" -> {
                val incoming = TimelineTurn(
                    turnId = frame.turnId ?: return,
                    // 事件里没有 turn_index（那是 TimelineTurn 的字段）：
                    // 已有 turn（分页加载）保留其序号，新 turn 用 createdSeq 兜底排序
                    turnIndex = long("turn_index") ?: Long.MAX_VALUE,
                    userText = str("user_text"),
                    state = "running",
                )
                // 合并而非覆盖：已有 turn（分页加载的完整数据）保留其 rounds
                val existing = _turns.value.firstOrNull { it.turnId == incoming.turnId }
                val merged = if (existing == null) incoming else incoming.copy(
                    rounds = existing.rounds.ifEmpty { incoming.rounds },
                    turnIndex = if (existing.turnIndex != 0L) existing.turnIndex else incoming.turnIndex,
                )
                _turns.value.filterNot { it.turnId == merged.turnId } + merged
            }

            "block_opened" -> mutateRoundBlocks(frame) { blocks ->
                val block = nestedBlock()
                val blockId = block?.let { (it["block_id"] as? JsonPrimitive)?.content }
                if (block == null || blockId == null) return@mutateRoundBlocks null
                val newBlock = TimelineBlock(
                    blockId = blockId,
                    blockOrder = (block["block_order"] as? JsonPrimitive)?.content?.toIntOrNull() ?: blocks.size,
                    kind = (block["kind"] as? JsonPrimitive)?.content ?: "text",
                    state = (block["state"] as? JsonPrimitive)?.content ?: "open",
                    text = (block["text"] as? JsonPrimitive)?.content,
                    tool = block["tool"]?.let { decodeTool(it) },
                )
                // SSE 重放会重复投递：按 block_id 替换而非追加
                blocks.filterNot { it.blockId == newBlock.blockId } + newBlock
            }

            "text_delta" -> mutateBlock(frame, str("block_id") ?: return) { block ->
                block.copy(text = (block.text ?: "") + (str("delta") ?: ""))
            }

            "block_checkpoint" -> mutateBlock(frame, str("block_id") ?: return) { block ->
                val overwrite = str("text")
                val increment = str("arg") ?: ""
                when {
                    !overwrite.isNullOrEmpty() -> block.copy(text = overwrite)
                    increment.isNotEmpty() -> block.copy(text = (block.text ?: "") + increment)
                    else -> block
                }
            }

            "tool_updated" -> mutateBlock(frame, str("block_id") ?: return) { block ->
                block.copy(tool = data["tool"]?.let { decodeTool(it) } ?: block.tool)
            }

            "tool_progress" -> mutateBlock(frame, str("block_id") ?: return) { block ->
                val chunk = str("chunk") ?: return@mutateBlock block
                val tool = block.tool ?: TimelineTool()
                // 对齐 TUI：进度增量进 progress（8K 截尾保尾部，输出结论在最后），不污染 output
                val merged = tool.progress + chunk
                val over = merged.length - MAX_PROGRESS_LEN
                block.copy(
                    tool = tool.copy(
                        progress = if (over > 0) merged.substring(over) else merged,
                        progressTruncated = tool.progressTruncated || over > 0,
                    )
                )
            }

            "block_sealed" -> mutateBlock(frame, str("block_id") ?: return) { block ->
                block.copy(state = "sealed")
            }

            "round_sealed" -> mutateTurn(frame) { turn ->
                turn.copy(rounds = turn.rounds.map {
                    if (it.roundNum == frame.roundNum) it.copy(sealed = true) else it
                })
            }

            "turn_sealed" -> mutateTurn(frame) { turn ->
                turn.copy(sealed = true, state = str("state") ?: "completed")
            }

            else -> _turns.value
        }

        // todo 工具块 → 待办清单（write 整表替换 / update 单条 delta；畸形忽略）
        val tool = when (type) {
            "block_opened" -> nestedBlock()?.get("tool")?.let { decodeTool(it) }
            "tool_updated" -> data["tool"]?.let { decodeTool(it) }
            else -> null
        }
        if (tool != null && com.qaqh.harness.data.ringing.model.TodoState.isTodoTool(tool.name)) {
            val next = when (tool.name?.lowercase()) {
                "todo_update" -> _todos.value?.let {
                    com.qaqh.harness.data.ringing.model.TodoState.parseUpdate(tool.argsJson, it)
                }
                else -> com.qaqh.harness.data.ringing.model.TodoState.parseWrite(tool.argsJson, _todos.value)
            }
            next?.let { _todos.value = it }
        }
    }

    private fun decodeTool(element: kotlinx.serialization.json.JsonElement): TimelineTool? =
        runCatching { connection.api.json.decodeFromJsonElement(TimelineTool.serializer(), element) }.getOrNull()

    /** 定位 frame 所属 turn（优先 turn_id，缺失时落到最后一个未密封 turn），对其 round 的 blocks 施加变换。 */
    private fun mutateRoundBlocks(
        frame: TimelineEntryFrame,
        transform: (List<TimelineBlock>) -> List<TimelineBlock>?,
    ): List<TimelineTurn> = mutateTurn(frame) { turn ->
        val roundNum = frame.roundNum ?: (turn.rounds.maxOfOrNull { it.roundNum } ?: 0)
        val rounds = turn.rounds.ifEmpty { listOf(TimelineRound(roundNum = roundNum)) }
        val idx = rounds.indexOfFirst { it.roundNum == roundNum }.let { if (it >= 0) it else rounds.size }
        val round = rounds.getOrElse(idx) { TimelineRound(roundNum = roundNum) }
        val newBlocks = transform(round.blocks) ?: return@mutateTurn turn
        val newRound = round.copy(blocks = newBlocks)
        turn.copy(rounds = buildList {
            addAll(rounds)
            if (idx < size) set(idx, newRound) else add(newRound)
        })
    }

    private fun mutateBlock(frame: TimelineEntryFrame, blockId: String, transform: (TimelineBlock) -> TimelineBlock): List<TimelineTurn> =
        mutateTurn(frame) { turn ->
            turn.copy(rounds = turn.rounds.map { round ->
                if (round.blocks.none { it.blockId == blockId }) round
                else round.copy(blocks = round.blocks.map { if (it.blockId == blockId) transform(it) else it })
            })
        }

    private fun mutateTurn(frame: TimelineEntryFrame, transform: (TimelineTurn) -> TimelineTurn): List<TimelineTurn> {
        val list = _turns.value
        val idx = frame.turnId?.let { id -> list.indexOfFirst { it.turnId == id } }
            ?.takeIf { it >= 0 }
            ?: list.indexOfLast { !it.sealed }.takeIf { it >= 0 }
            ?: return list
        return list.toMutableList().apply { set(idx, transform(get(idx))) }
    }
}

private const val TAG = "SessionTimeline"

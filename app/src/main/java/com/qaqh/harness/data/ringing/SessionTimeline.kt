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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
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

    /** 首屏加载 + 订阅实时增量。 */
    fun start(onError: (String) -> Unit) {
        if (eventsJob != null) return
        eventsJob = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    _status.value = Status.Loading
                    loadPage()
                    // SSE 订阅循环：租约轮换（重连/切会话）后自动带新租约重订阅
                    while (isActive) {
                        val sid = awaitLease()
                        try {
                            connection.api.timelineEvents(sid, sessionId).collect { ev ->
                                when (ev) {
                                    is RingingApi.TimelineSseEvent.Entry -> applyFrame(ev.frame)
                                    RingingApi.TimelineSseEvent.Terminated -> Unit
                                }
                            }
                        } catch (e: RingingApi.ApiException) {
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

    fun stop() {
        eventsJob?.cancel()
        eventsJob = null
    }

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
                _sendError.value = "命令未受理：${ack.code ?: ""} ${ack.message ?: ""}".trim()
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
    }

    private suspend fun awaitLease(): String =
        connection.state.first { it is RingingConnection.State.Connected }
            .let { (it as RingingConnection.State.Connected).clientSessionId }

    // ---------- 事件 reducer ----------

    private fun applyFrame(frame: TimelineEntryFrame) {
        val type = frame.type ?: return
        val data = frame.event as? JsonObject ?: return
        fun str(key: String): String? = (data[key] as? JsonPrimitive)?.content
        fun long(key: String): Long? = (data[key] as? JsonPrimitive)?.content?.toLongOrNull()

        _turns.value = when (type) {
            "turn_opened" -> {
                val incoming = TimelineTurn(
                    turnId = frame.turnId ?: return,
                    turnIndex = long("turn_index") ?: Int.MAX_VALUE.toLong(),
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
                val newBlock = TimelineBlock(
                    blockId = str("block_id") ?: return@mutateRoundBlocks null,
                    blockOrder = (data["block_order"] as? JsonPrimitive)?.content?.toIntOrNull() ?: blocks.size,
                    kind = str("kind") ?: "text",
                    state = "open",
                    text = str("text"),
                    tool = data["tool"]?.let { decodeTool(it) },
                )
                // SSE 重放会重复投递：按 block_id 替换而非追加
                blocks.filterNot { it.blockId == newBlock.blockId } + newBlock
            }

            "text_delta" -> mutateBlock(frame, str("block_id") ?: return) { block ->
                block.copy(text = (block.text ?: "") + (str("delta") ?: ""))
            }

            "tool_updated" -> mutateBlock(frame, str("block_id") ?: return) { block ->
                block.copy(tool = data["tool"]?.let { decodeTool(it) } ?: block.tool)
            }

            "block_sealed" -> mutateBlock(frame, str("block_id") ?: return) { block ->
                block.copy(state = "sealed", text = str("text") ?: block.text)
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

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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 单会话聊天时间线状态器：timeline 分页做底 + [TimelineStream] 的共享实时帧做增量，
 * reducer 语义对齐 webui session/reducer.ts。
 *
 * 自己不碰 SSE——整条流的订阅由 [TimelineStream] 独占（待办通知是另一个消费方）。
 */
class SessionTimeline(
    private val connection: RingingConnection,
    val sessionId: String,
    private val scope: CoroutineScope,
    private val stream: TimelineStream,
) {
    private val _turns = MutableStateFlow<List<TimelineTurn>>(emptyList())
    val turns: StateFlow<List<TimelineTurn>> = _turns.asStateFlow()

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    /** 当前待办清单：与实况通知同源，由 [TimelineStream] 统一从工具块摄取。 */
    val todos: StateFlow<com.qaqh.harness.data.ringing.model.TodoState?> = stream.todos

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
     * 合并前它还用作 SSE 的 `Last-Event-ID` 游标；现在游标归 [TimelineStream] 管，
     * 这里只用来把回放缓存里已被 REST 页覆盖的帧滤掉。
     */
    private var lastSeq: Long? = null

    /** 首屏加载 + 接上共享实时流。 */
    fun start(onError: (String) -> Unit) {
        if (eventsJob != null) return
        eventsJob = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    _status.value = Status.Loading
                    loadPage()
                }
                // 水位到回放缓存起点之间若已被挤掉，补一页再订阅——否则那段增量永久丢失
                //（流式正文会缺一截，要到块定稿才自愈）
                val oldestCached = stream.oldestBufferedSeq()
                if (oldestCached != null && oldestCached > (lastSeq ?: 0L) + 1) {
                    android.util.Log.i(TAG, "replay gap: watermark=$lastSeq oldest=$oldestCached — re-paging")
                    withContext(Dispatchers.IO) { loadPage() }
                }
                // SharedFlow 保证"回放缓存 + 后续实时"原子且有序。水位之前的帧 REST 页里
                // 已经有了，必须丢掉：text_delta 不幂等，重复施加会把文字重复一遍。
                stream.frames.collect { frame ->
                    if (frame.timelineSeq > (lastSeq ?: 0L)) applyFrame(frame)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _status.value = Status.Error(e.message ?: e.javaClass.simpleName)
                onError(e.message ?: "timeline 初始化失败")
            }
        }
    }

    private companion object {
        const val TAG = "SessionTimeline"

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

    /** 消费发送错误（Snackbar 展示后调用，避免同一条重复触发）。 */
    fun consumeSendError() {
        _sendError.value = null
    }

    suspend fun send(text: String): CommandAck? {
        _sendError.value = null
        return try {
            val ack = withContext(Dispatchers.IO) { connection.sendUserMessage(text) }
            if (ack.status != "accepted") {
                _sendError.value = when (ack.code) {
                    "not_driver" -> "命令未受理：另一客户端正在驾驶该会话（${ack.message ?: "driver seat occupied"}）"
                    // view 档设备发会话命令会被 scope 门拒（需要 interact 档）
                    "insufficient_scope" -> "权限不足：当前设备档位无法执行该操作（需要 interact）"
                    "interaction_already_resolved" -> "该请求已被处理（电脑端已应答），无需重复操作"
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
                    // SSE 重放不得把已定稿的回合复活成 running：sealed/state 以已落地的
                    // 一方为准。定稿回合被复活后既不再折叠过程，又会作为"未密封回合"
                    // 抢走后续帧的归属。
                    sealed = existing.sealed,
                    state = if (existing.sealed) existing.state else incoming.state,
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
        // 待办清单不在这里算：[TimelineStream] 是同一个帧的另一个消费方，
        // 两处各算一遍正是合并前状态会互相矛盾的原因。
    }

    private fun decodeTool(element: kotlinx.serialization.json.JsonElement): TimelineTool? =
        com.qaqh.harness.data.ringing.decodeTimelineTool(connection.api, element)

    /** 定位 frame 所属 turn（优先 turn_id，缺失时落到最新的未密封 turn），对其 round 的 blocks 施加变换。 */
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
            ?: newestUnsealedIndex(list)
            ?: return list
        return list.toMutableList().apply { set(idx, transform(get(idx))) }
    }

    /**
     * 帧没带 turn_id（或指向还没落地的回合）时的兜底归属：最新的未密封回合。
     * 不能按列表位置取——loadPage/refreshLatest 按 turn_index 重排、turn_opened 却直接
     * 追加，列表序不等于时序；`indexOfLast { !sealed }` 于是会把新回合的帧塞进更老的
     * 未密封回合（表现即"消息越长越容易跳到早期信息"）。turn_index 缺省为 0 的旧回合
     * 用 created_seq 兜底，SSE 现场新开的回合（turn_index=MAX）自然胜出。
     */
    private fun newestUnsealedIndex(list: List<TimelineTurn>): Int? {
        var best = -1
        var bestKey = Long.MIN_VALUE
        list.forEachIndexed { i, turn ->
            if (turn.sealed) return@forEachIndexed
            val key = if (turn.turnIndex != 0L) turn.turnIndex else turn.createdSeq
            if (best < 0 || key >= bestKey) {
                best = i
                bestKey = key
            }
        }
        return best.takeIf { it >= 0 }
    }
}

private const val TAG = "SessionTimeline"

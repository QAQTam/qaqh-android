package com.qaqh.harness

import com.qaqh.harness.data.ringing.RingingConnection
import com.qaqh.harness.data.ringing.SessionTimeline
import com.qaqh.harness.data.ringing.model.TimelineEntryFrame
import com.qaqh.harness.data.ringing.model.TimelineRound
import com.qaqh.harness.data.ringing.model.TimelineTurn
import com.qaqh.harness.ui.session.orderedBlocks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 时间线时序回归：助手正文必须按"思考 → 正文 → 工具"的真实发生顺序落位，
 * 且 SSE 重放不得把已定稿的回合复活（复活后它会继续抢走后续帧的归属）。
 */
class SessionTimelineOrderingTest {

    private var seq = 0L

    private fun timeline(): SessionTimeline {
        seq = 0
        val connection = RingingConnection(
            endpoint = "http://localhost:1",
            token = "test",
            sessionId = null,
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        return SessionTimeline(connection, "seed", CoroutineScope(Dispatchers.Unconfined))
    }

    private fun frame(turnId: String?, roundNum: Int?, event: JsonObject): TimelineEntryFrame =
        TimelineEntryFrame(timelineSeq = ++seq, turnId = turnId, roundNum = roundNum, event = event)

    private fun opened(turnId: String, userText: String, turnIndex: Long = 0): JsonObject =
        buildJsonObject {
            put("type", "turn_opened")
            put("turn_index", turnIndex)
            put("user_text", userText)
        }

    private fun blockOpened(blockId: String, kind: String, order: Int): JsonObject = buildJsonObject {
        put("type", "block_opened")
        putJsonObject("block") {
            put("block_id", blockId)
            put("block_order", order)
            put("kind", kind)
            put("state", "open")
        }
    }

    private fun delta(blockId: String, text: String): JsonObject = buildJsonObject {
        put("type", "text_delta")
        put("block_id", blockId)
        put("delta", text)
    }

    private fun sealed(type: String): JsonObject = buildJsonObject { put("type", type) }

    @Test
    fun `replayed turn_opened keeps a sealed turn sealed`() {
        val t = timeline()
        t.applyFrameForTest(frame("t1", 0, opened("t1", "第一问")))
        t.applyFrameForTest(frame("t1", 0, blockOpened("b1", "text", 0)))
        t.applyFrameForTest(frame("t1", 0, delta("b1", "答案")))
        t.applyFrameForTest(frame("t1", 0, sealed("turn_sealed")))
        assertTrue("前置：回合应已定稿", t.turnsForTest().single().sealed)

        // SSE 带游标重订阅会重放 turn_opened：不能把定稿回合改回 running
        t.applyFrameForTest(frame("t1", 0, opened("t1", "第一问")))
        val turn = t.turnsForTest().single()
        assertTrue("重放 turn_opened 把已定稿回合复活了", turn.sealed)
        assertEquals("completed", turn.state)
        assertEquals("答案", turn.rounds.single().blocks.single().text)
    }

    @Test
    fun `frames without turn_id go to the newest unsealed turn, not the last list slot`() {
        val t = timeline()
        // 现场：SSE 顺序不等于 turn 顺序（重放/分页合并都会打乱列表位置）。
        // 列表里最后一个未密封回合是"更早"的那个，帧不能按列表位置归属。
        t.applyFrameForTest(frame("t9", 0, opened("t9", "最新一问", turnIndex = 9)))
        t.applyFrameForTest(frame("t9", 0, blockOpened("b9", "text", 0)))
        t.applyFrameForTest(frame("t5", 0, opened("t5", "更早一问", turnIndex = 5)))
        t.applyFrameForTest(frame("t5", 0, blockOpened("b5", "text", 0)))

        // 帧不带 turn_id（服务端某些路径只带 round_num）：必须落到最新的未密封回合，
        // 否则 b9 在错误的回合里找不到，这条增量就凭空丢了
        t.applyFrameForTest(frame(null, 0, delta("b9", "新内容")))

        val newest = t.turnsForTest().first { it.turnId == "t9" }
        val older = t.turnsForTest().first { it.turnId == "t5" }
        assertEquals("新内容", newest.rounds.flattenToString())
        assertEquals("", older.rounds.flattenToString())
    }

    @Test
    fun `blocks render in round then block order, not grouped by kind`() {
        val turn = TimelineTurn(
            turnId = "t1",
            turnIndex = 1,
            rounds = listOf(
                TimelineRound(
                    roundNum = 1,
                    blocks = listOf(
                        block("r1-tool", "tool", order = 1),
                        block("r1-text", "text", order = 0),
                    ),
                ),
                TimelineRound(
                    roundNum = 0,
                    blocks = listOf(
                        block("r0-order2", "text", order = 2),
                        block("r0-order0", "reasoning", order = 0),
                        block("r0-order1", "text", order = 1),
                    ),
                ),
            ),
        )
        assertEquals(
            listOf("r0-order0", "r0-order1", "r0-order2", "r1-text", "r1-tool"),
            turn.orderedBlocks().map { it.blockId },
        )
    }

    private fun block(id: String, kind: String, order: Int) =
        com.qaqh.harness.data.ringing.model.TimelineBlock(blockId = id, blockOrder = order, kind = kind)

    private fun List<TimelineRound>.flattenToString(): String =
        flatMap { it.blocks }.joinToString("") { it.text ?: "" }
}

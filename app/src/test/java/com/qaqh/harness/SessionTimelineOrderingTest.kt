package com.qaqh.harness

import com.qaqh.harness.data.core.CoreConnection
import com.qaqh.harness.data.core.SessionEvent
import com.qaqh.harness.data.core.SessionState
import com.qaqh.harness.data.core.TurnView
import com.qaqh.harness.data.core.ToolCallView
import com.qaqh.harness.data.ringing.SessionTimeline
import com.qaqh.harness.data.ringing.model.TimelineBlock
import com.qaqh.harness.data.ringing.model.TimelineRound
import com.qaqh.harness.data.ringing.model.TimelineTurn
import com.qaqh.harness.ui.session.orderedBlocks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 时间线时序回归(v3 reducer):正文按"输入 → 思考 → 正文 → 工具"的发生顺序落位;
 * 事件重放不得把已定稿的回合复活;乱序到达的块也要正确归属。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionTimelineOrderingTest {

    private fun timeline(): SessionTimeline =
        SessionTimeline(
            CoreConnection(
                endpoint = "http://localhost:1",
                token = "test",
                tlsFingerprint = null,
                scope = CoroutineScope(Dispatchers.Unconfined),
            ),
            "seed",
            CoroutineScope(Dispatchers.Unconfined),
        )

    private fun inputEntry(turnId: String, blockId: String, text: String): JsonObject = buildJsonObject {
        put("kind", "input")
        putJsonObject("data") {
            put("block_id", blockId)
            put("turn_id", turnId)
            put("purpose", "trigger_turn")
            putJsonObject("content") {
                put("kind", "inline")
                putJsonObject("data") { put("text", text) }
            }
        }
    }

    private fun assistantEntry(turnId: String, blockId: String, kind: String, text: String): JsonObject =
        buildJsonObject {
            put("kind", "assistant_block")
            putJsonObject("data") {
                put("block_id", blockId)
                put("turn_id", turnId)
                put("block_kind", kind)
                putJsonObject("content") {
                    put("kind", "inline")
                    putJsonObject("data") { put("text", text) }
                }
            }
        }

    @Test
    fun `replayed turn_started keeps a sealed turn sealed`() {
        val t = timeline()
        t.applyEventForTest(SessionEvent.TurnStarted("t1"))
        t.applyEventForTest(SessionEvent.TimelineAppended(inputEntry("t1", "in1", "第一问")))
        t.applyEventForTest(SessionEvent.MessageDelta("t1", "b1", "answer", "答案"))
        t.applyEventForTest(
            SessionEvent.TurnEnded(
                "t1",
                buildJsonObject {
                    put("kind", "finished")
                    putJsonObject("data") { put("terminal", "completed") }
                },
            ),
        )
        assertTrue("前置:回合应已定稿", t.turnsForTest().single().sealed)

        // 流重订阅会重放事件:不能把定稿回合改回 running(v3 ensureTurn 幂等)
        t.applyEventForTest(SessionEvent.TurnStarted("t1"))
        val turn = t.turnsForTest().single()
        assertTrue("重放把已定稿回合复活了", turn.sealed)
        assertEquals("completed", turn.state)
        assertEquals("第一问", turn.userText)
        assertEquals("答案", turn.rounds.single().blocks.single { it.blockId == "b1" }.text)
    }

    @Test
    fun `message_delta for an unseen turn creates the turn (out-of-order arrival)`() {
        val t = timeline()
        // 事件顺序不保证 turn_started 先到(重连窗口):MessageDelta 必须自建回合归属
        t.applyEventForTest(SessionEvent.MessageDelta("t2", "b2", "answer", "先到的增量"))
        val turn = t.turnsForTest().single()
        assertEquals("t2", turn.turnId)
        assertEquals("先到的增量", turn.rounds.single().blocks.single().text)
    }

    @Test
    fun `message_delta replaces instead of appending (v3 replace semantics)`() {
        val t = timeline()
        t.applyEventForTest(SessionEvent.TurnStarted("t1"))
        t.applyEventForTest(SessionEvent.MessageDelta("t1", "b1", "answer", "片段一"))
        t.applyEventForTest(SessionEvent.MessageDelta("t1", "b1", "answer", "片段一片段二"))
        assertEquals("片段一片段二", t.turnsForTest().single().rounds.single().blocks.single().text)
    }

    @Test
    fun `bootstrap rebuilds turns from state snapshot`() {
        val t = timeline()
        t.applyBootstrapForTest(
            SessionState(
                seq = 42,
                turns = listOf(TurnView(turnId = "t1", phase = "running")),
                timeline = listOf(
                    inputEntry("t1", "in1", "快照问题"),
                    buildJsonObject {
                        put("kind", "tool_call")
                        putJsonObject("data") {
                            put("call_id", "call1")
                            put("turn_id", "t1")
                        }
                    },
                    assistantEntry("t1", "b1", "answer", "快照回答"),
                ),
                tools = listOf(
                    ToolCallView(
                        callId = "call1",
                        turnId = "t1",
                        toolName = "read_file",
                        state = buildJsonObject { put("kind", "running") },
                    ),
                ),
            ),
        )
        val turn = t.turnsForTest().single()
        assertEquals("快照问题", turn.userText)
        val blocks = turn.rounds.single().blocks
        // blockOrder = 块序(0,1,...),不是快照 seq;顺序 = timeline 发生序(tool 先于回答)
        assertEquals(listOf(0, 1), blocks.map { it.blockOrder })
        assertEquals(listOf("tool", "text"), blocks.map { it.kind })
        assertEquals("read_file", blocks.first { it.kind == "tool" }.tool?.name)
        assertEquals("running", blocks.first { it.kind == "tool" }.tool?.state)
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
        TimelineBlock(blockId = id, blockOrder = order, kind = kind)
}

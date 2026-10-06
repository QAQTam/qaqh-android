package com.qaqh.harness

import com.qaqh.harness.data.ringing.RingingConnection
import com.qaqh.harness.data.ringing.SessionTimeline
import com.qaqh.harness.data.ringing.model.TimelineEntryFrame
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
 * 流式渲染 reducer 的 CPU/内存基准（JVM，无 Android 依赖路径）。
 *
 * 回答两个问题：
 * 1. 高频 text_delta 的摊销成本（每帧 µs 级 = O(delta)，与回合/块总数无关地保持平稳）；
 * 2. 长流不堆积：灌几十万字符后 GC 后驻留内存只与最终文本大小相关（无树/对象累积）。
 *
 * 阈值刻意宽松（CI 机器抖动），数值打印出来供人工趋势对比；真正红线是内存断言。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionTimelineReducerPerfTest {

    private companion object {
        const val TURN_ID = "t1"
        const val BLOCK_ID = "b1"
        const val DELTA = "你好，世界，流式渲染测试。" // 每帧 12 个字符
    }

    private lateinit var timeline: SessionTimeline
    private var seq = 0L

    private fun newTimeline(): SessionTimeline {
        seq = 0
        val connection = RingingConnection(
            endpoint = "http://localhost:1",
            token = "test",
            sessionId = null,
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        return SessionTimeline(connection, "seed", CoroutineScope(Dispatchers.Unconfined))
    }

    private fun frame(event: JsonObject): TimelineEntryFrame =
        TimelineEntryFrame(timelineSeq = ++seq, turnId = TURN_ID, roundNum = 0, event = event)

    private fun seedTurnAndBlock() {
        timeline.applyFrameForTest(
            frame(
                buildJsonObject {
                    put("type", "turn_opened")
                    put("user_text", "bench")
                }
            )
        )
        timeline.applyFrameForTest(
            frame(
                buildJsonObject {
                    put("type", "block_opened")
                    putJsonObject("block") {
                        put("block_id", BLOCK_ID)
                        put("block_order", 0)
                        put("kind", "text")
                        put("state", "open")
                    }
                }
            )
        )
    }

    private fun textDelta(n: Int): TimelineEntryFrame =
        frame(
            buildJsonObject {
                put("type", "text_delta")
                put("block_id", BLOCK_ID)
                put("fragment_seq", n)
                put("delta", DELTA)
            }
        )

    private fun usedMemoryAfterGc(): Long {
        repeat(3) {
            System.gc()
            Thread.sleep(20)
        }
        val rt = Runtime.getRuntime()
        return rt.totalMemory() - rt.freeMemory()
    }

    @Test
    fun `text_delta throughput stays flat as turn grows`() {
        timeline = newTimeline()
        seedTurnAndBlock()

        // 预热（JIT）：不算入测量
        repeat(1_000) { timeline.applyFrameForTest(textDelta(it)) }

        val frames = 10_000
        val t0 = System.nanoTime()
        repeat(frames) { timeline.applyFrameForTest(textDelta(it)) }
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000.0
        val usPerFrame = elapsedMs * 1_000 / frames

        // 正确性：delta 无损累加（顺带验证 text_delta 路径没有丢帧）
        val textLength = textLengthOfFirstBlock()
        assertEquals((frames + 1_000).toLong() * DELTA.length, textLength.toLong())

        println(
            "PERF text_delta: total=${"%.1f".format(elapsedMs)}ms for $frames frames " +
                "(${String.format("%.2f", usPerFrame)}µs/frame), block text=${textLength / 1024}KB"
        )
        // 宽松红线：若有人把 reducer 改成每帧全量重排/重解析，这里会先爆
        assertTrue("reducer 太慢：$usPerFrame µs/frame", usPerFrame < 500.0)
    }

    @Test
    fun `long stream does not accumulate retained memory`() {
        timeline = newTimeline()
        seedTurnAndBlock()

        val frames = 30_000 // ~350KB 纯文本增量
        val before = usedMemoryAfterGc()
        repeat(frames) { timeline.applyFrameForTest(textDelta(it)) }
        val after = usedMemoryAfterGc()
        val growthMb = (after - before) / 1024.0 / 1024.0

        println("PERF memory: stream $frames deltas (+${frames * DELTA.length / 1024}KB text), retained growth=${"%.2f".format(growthMb)}MB")
        // 驻留增长只应与文本本体同量级（几百 KB）；树/对象累积会让它涨到几十 MB。
        // 64MB 是宽容红线——正常实现应在个位数 MB。
        assertTrue("内存驻留增长异常：${growthMb}MB（疑似堆积）", growthMb < 64.0)
    }

    private fun textLengthOfFirstBlock(): Int =
        timeline.turnsForTest().first { it.turnId == TURN_ID }
            .rounds.flatMap { it.blocks }.first { it.blockId == BLOCK_ID }
            .text?.length ?: 0
}

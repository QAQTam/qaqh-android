package com.qaqh.harness

import com.qaqh.harness.data.core.CoreConnection
import com.qaqh.harness.data.core.SessionEvent
import com.qaqh.harness.data.ringing.SessionTimeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 流式渲染 reducer 的 CPU/内存基准(JVM,无 Android 依赖路径)——v3 替换语义版。
 *
 * v3 的 MessageDelta 是**替换语义**(每帧携带累计文本),成本模型从 O(delta) 变为
 * O(累计长度):单帧成本与块长度线性、与回合/块总数无关。阈值因此比 v2 宽松一个
 * 量级;真正红线仍是内存断言(长流 GC 后驻留只与最终文本大小相关)。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionTimelineReducerPerfTest {

    private companion object {
        const val TURN_ID = "t1"
        const val BLOCK_ID = "b1"
        const val DELTA = "你好,世界,流式渲染测试。" // 每帧 12 个字符
    }

    private fun newTimeline(): SessionTimeline =
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

    private fun seedTurn(t: SessionTimeline) {
        t.applyEventForTest(SessionEvent.TurnStarted(TURN_ID))
    }

    /** v3 替换语义:第 n 帧携带累计文本(前 n 段)。 */
    private fun cumulativeDelta(n: Int): SessionEvent.MessageDelta =
        SessionEvent.MessageDelta(TURN_ID, BLOCK_ID, "answer", DELTA.repeat(n + 1))

    private fun usedMemoryAfterGc(): Long {
        repeat(3) {
            System.gc()
            Thread.sleep(20)
        }
        val rt = Runtime.getRuntime()
        return rt.totalMemory() - rt.freeMemory()
    }

    @Test
    fun `replace-delta throughput stays linear-friendly as block grows`() {
        val timeline = newTimeline()
        seedTurn(timeline)

        // 预热(JIT):不算入测量
        repeat(200) { timeline.applyEventForTest(cumulativeDelta(it)) }

        val frames = 2_000
        val t0 = System.nanoTime()
        repeat(frames) { timeline.applyEventForTest(cumulativeDelta(200 + it)) }
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000.0
        val usPerFrame = elapsedMs * 1_000 / frames

        // 正确性:替换语义 → 最终文本 = 累计前缀(无重复拼接)
        val textLength = timeline.turnsForTest().single()
            .rounds.single().blocks.single { it.blockId == BLOCK_ID }.text?.length ?: 0
        assertEquals((frames + 200).toLong() * DELTA.length, textLength.toLong())

        println(
            "PERF message_delta(replace): total=${"%.1f".format(elapsedMs)}ms for $frames frames " +
                "(${String.format("%.2f", usPerFrame)}µs/frame), block text=${textLength / 1024}KB",
        )
        // 宽松红线:替换路径全量拷贝,单帧 ~26KB;若有人改成每帧重排整棵回合树会先爆
        org.junit.Assert.assertTrue(
            "per-frame cost exploded: $usPerFrame µs",
            usPerFrame < 5_000.0,
        )
    }

    @Test
    fun `long stream does not retain history beyond final text`() {
        val timeline = newTimeline()
        seedTurn(timeline)
        val frames = 1_500
        repeat(frames) { timeline.applyEventForTest(cumulativeDelta(it)) }
        val baseline = usedMemoryAfterGc()
        repeat(300) { timeline.applyEventForTest(cumulativeDelta(frames + it)) }
        System.gc()
        val after = usedMemoryAfterGc()
        val finalText = timeline.turnsForTest().single()
            .rounds.single().blocks.single().text.orEmpty()
        // 允许抖动:驻留增量不得超过「最终文本体积 × 8 + 4MB」——防历史帧/中间串堆积
        val finalBytes = (frames + 300) * DELTA.length * 2 // UTF-16
        org.junit.Assert.assertTrue(
            "retention grew by ${after - baseline} bytes (final text $finalBytes)",
            after - baseline < finalBytes * 8 + 4L * 1024 * 1024,
        )
        assertEquals((frames + 300).toLong() * DELTA.length, finalText.length.toLong())
    }
}

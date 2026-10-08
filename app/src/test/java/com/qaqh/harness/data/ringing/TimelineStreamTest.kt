package com.qaqh.harness.data.ringing

import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import com.qaqh.harness.data.ringing.model.TimelineEntryFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 合并的意义全在"一条订阅喂两个消费方"上，所以这三条直接盯着它：
 * 1. 两个消费方在场也只发一次 `timeline/events`（合并前是两处各订一遍）；
 * 2. 聊天屏是**后加入**的消费方——它先拉 REST 页拿到 watermark，回放缓存里的尾巴
 *    必须既不缺口也不重复（text_delta 不幂等，重复施加会把文字重复一遍）；
 * 3. 切会话要重置游标与回放缓存，上一个会话的帧不得漏给新会话。
 *
 * SSE 用 chunkedBody + throttleBody 拖成"边流边不结束"的长响应，这样测试断言窗口内
 * 不会因 mock 收尾而重订阅；每帧的 JSON 必须写成一行——data: 字段遇换行即结束。
 */
@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [35])
class TimelineStreamTest {

    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val timelineSubscribes = AtomicInteger()

    private companion object {
        const val SEED_A = "sess-a"
        const val SEED_B = "sess-b"

        /** 4 帧头部先到（~0.6s），pad 把响应吊到 ~4s，断言窗口内订阅数保持 1。 */
        const val PAD_LINES = 400

        fun frame(body: String) =
            "event: timeline.entry\ndata: {\"schema\":\"x\",\"session_id\":\"s\",\"entry\":$body}\n\n"

        fun turnOpened(seq: Long, turnId: String, userText: String) = frame(
            """{"timeline_seq":$seq,"turn_id":"$turnId","round_num":0,"event":{"type":"turn_opened","user_text":"$userText"}}""",
        )

        fun blockOpened(seq: Long, turnId: String, blockId: String) = frame(
            """{"timeline_seq":$seq,"turn_id":"$turnId","round_num":0,"event":{"type":"block_opened","block":{"block_id":"$blockId","block_order":0,"kind":"text","state":"open"}}}""",
        )

        fun textDelta(seq: Long, turnId: String, blockId: String, delta: String) = frame(
            """{"timeline_seq":$seq,"turn_id":"$turnId","round_num":0,"event":{"type":"text_delta","block_id":"$blockId","delta":"$delta"}}""",
        )

        fun todoWrite(seq: Long, turnId: String, title: String) = frame(
            """{"timeline_seq":$seq,"turn_id":"$turnId","round_num":0,"event":{"type":"block_opened","block":{"block_id":"todo-$seq","block_order":0,"kind":"tool","state":"sealed","tool":{"name":"todo_write","state":"succeeded","args_json":"{\"items\":[{\"id\":\"1\",\"title\":\"$title\",\"status\":\"in_progress\"}]}"}}}}""",
        )

        /** keepalive 注释行：不成帧，只把响应吊住。 */
        fun pad(lines: Int = PAD_LINES) = buildString { repeat(lines) { append(": keepalive\n\n") } }
    }

    @Before
    fun setUp() {
        server.start()
    }

    @After
    fun tearDown() {
        // 先停客户端再关 server：否则 close() 会等正在节流的 body writer，
        // 而我们的重连循环（退避 1s）会不断把新连接塞进去，close 永远收不了尾
        scope.cancel()
        server.close()
    }

    private fun json(body: String) =
        MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(body).build()

    private fun sse(body: String) = MockResponse.Builder()
        .code(200)
        .addHeader("Content-Type", "text/event-stream")
        .chunkedBody(body, 64)
        .throttleBody(512, 40, TimeUnit.MILLISECONDS)
        .build()

    /** REST timeline 页：模拟"页里已经含前 watermark 帧的结果"。 */
    private fun timelinePage(watermark: Long, text: String) = json(
        """{"snapshot":{"watermark":$watermark,"turns":[{"turn_id":"t1","turn_index":1,"created_seq":1,
            "user_text":"问","sealed":false,"state":"running",
            "rounds":[{"round_num":0,"sealed":false,"is_final":false,
              "blocks":[{"block_id":"b1","block_order":0,"kind":"text","state":"open","text":"$text"}]}]}]},
           "has_more":false,"total_turns":1}""",
    )

    private fun serve(timelineBody: (session: String) -> String, page: () -> MockResponse) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url?.encodedPath.orEmpty()
                // /ringing/v2/sessions/{seed}/... —— 第 5 段才是 seed
                val session = path.split("/").getOrNull(4) ?: ""
                return when {
                    path.endsWith("/clients/open") -> json(
                        """{"schema":"qaqh.Ringing","version":2,"accepted":true,
                            "client_session_id":"cs-1","server_epoch":"epoch-1",
                            "lease_ttl_ms":30000,"renew_interval_ms":10000,
                            "capabilities":{"subscribe":true,"interact":true,"drive":true,"timeline":true,
                                            "service":true,"content":true,"single_stream":true}}""",
                    )

                    path.endsWith("/leases/renew") ->
                        json("""{"ok":true,"lease_ttl_ms":30000,"renew_interval_ms":10000}""")

                    path.endsWith("/bootstrap") -> json(
                        """{"schema":"qaqh.Ringing","version":2,"server_epoch":"epoch-1","session_id":"$session",
                            "snapshot_cursor":"v2.SNAP","control":{"channel":"control","state_revision":1,
                            "snapshot_version":1,"state":{"activity":"running"}}}""",
                    )

                    path.endsWith("/commands/control") -> {
                        val cmdId = Regex("\"command_id\"\\s*:\\s*\"([^\"]+)\"")
                            .find(request.body?.utf8().orEmpty())?.groupValues?.get(1) ?: "cmd-1"
                        json("""{"command_id":"$cmdId","status":"accepted"}""")
                    }

                    path.endsWith("/timeline/events") -> {
                        timelineSubscribes.incrementAndGet()
                        sse(timelineBody(session))
                    }

                    path.endsWith("/timeline") -> page()

                    path.endsWith("/events") -> sse(pad())

                    else -> MockResponse.Builder().code(404).body("""{"code":"not_found"}""").build()
                }
            }
        }
    }

    private suspend fun connected(): RingingConnection {
        val conn = RingingConnection(
            endpoint = server.url("/").toString().trimEnd('/'),
            token = "test-token",
            sessionId = SEED_A,
            scope = scope,
        )
        conn.start()
        withTimeout(20_000) { conn.leases.first() }
        return conn
    }

    /** 前三帧的结果会被 REST 页覆盖；第 4 帧是页里没有的正文尾巴；第 5 帧喂待办。 */
    private val headFrames =
        turnOpened(1, "t1", "问") + blockOpened(2, "t1", "b1") + textDelta(3, "t1", "b1", "半句")

    private val fullStreamBody = headFrames + textDelta(4, "t1", "b1", "续上") + todoWrite(5, "t1", "锁定任务")

    @Test
    fun `single subscription serves both the frame consumer and the todo consumer`() = runBlocking {
        serve({ fullStreamBody + pad() }) { timelinePage(watermark = 0, text = "") }
        val conn = connected()
        val stream = TimelineStream(conn, scope)

        // 消费方 A：原始帧（聊天屏 reducer 的位置）
        val frames = Collections.synchronizedList(mutableListOf<TimelineEntryFrame>())
        scope.launch { stream.frames.collect { frames.add(it) } }
        // 消费方 B：待办（实况通知的位置）
        scope.launch { stream.todos.collect { } }
        stream.start()

        withTimeout(20_000) { while (frames.size < 5) delay(50) }
        assertEquals("两个消费方应当只发一次订阅", 1, timelineSubscribes.get())
        assertEquals("待办必须由同一条流派生", "锁定任务", stream.todos.value?.items?.firstOrNull()?.title)
        stream.stop()
    }

    @Test
    fun `late subscriber resumes from its REST watermark without duplicating text`() = runBlocking {
        // 四帧全在头部：前三帧的结果已被 REST 页（watermark=3、正文"半句"）覆盖，
        // 第四帧是页里没有的尾巴。聊天屏中途打开时两者都要处理对。
        serve({ fullStreamBody + pad() }) { timelinePage(watermark = 3, text = "半句") }
        val conn = connected()
        val stream = TimelineStream(conn, scope)
        stream.start()
        withTimeout(20_000) { while (stream.oldestBufferedSeq() == null) delay(50) }

        val timeline = SessionTimeline(conn, SEED_A, scope, stream)
        timeline.start(onError = { })
        // 缓存里的 1..3 必须被水位滤掉，否则"半句"会变成"半句半句"；第 4 帧要照常续上
        withTimeout(20_000) { while (textOf(timeline) != "半句续上") delay(50) }
        assertEquals("半句续上", textOf(timeline))
        assertEquals("整条流仍应只有一次订阅", 1, timelineSubscribes.get())
        timeline.stop()
        stream.stop()
    }

    @Test
    fun `switching session resets cursor and replay cache`() = runBlocking {
        serve(
            { session ->
                if (session == SEED_A) turnOpened(1, "ta", "A 会话的问题") + pad()
                else turnOpened(9, "tb", "B 会话的问题") + pad()
            },
        ) { timelinePage(watermark = 0, text = "") }
        val conn = connected()
        val stream = TimelineStream(conn, scope)
        val frames = Collections.synchronizedList(mutableListOf<TimelineEntryFrame>())
        scope.launch { stream.frames.collect { frames.add(it) } }
        stream.start()

        withTimeout(20_000) { while (frames.isEmpty()) delay(50) }
        assertTrue("A 会话的帧应已进缓存", stream.oldestBufferedSeq() != null)

        conn.setSessionId(SEED_B)
        val before = frames.size
        withTimeout(20_000) { while (frames.size <= before) delay(50) }
        assertEquals(9L, frames.last().timelineSeq)

        // 缓存重置的真实保证：切会话**之后**才加入的消费方，不得看到 A 会话的帧。
        // （直接断言 oldestBufferedSeq()==null 有竞态——重置与新帧入缓存之间只有几十毫秒）
        val late = Collections.synchronizedList(mutableListOf<Long>())
        scope.launch { stream.frames.collect { late.add(it.timelineSeq) } }
        withTimeout(20_000) { while (late.isEmpty()) delay(50) }
        assertTrue(
            "旧会话的尾巴不得回放给新会话的消费方",
            late.none { it == 1L },
        )
        assertTrue(late.contains(9L))
        stream.stop()
    }

    private fun textOf(timeline: SessionTimeline): String =
        timeline.turns.value.flatMap { it.rounds }.flatMap { it.blocks }
            .joinToString("") { it.text ?: "" }
}

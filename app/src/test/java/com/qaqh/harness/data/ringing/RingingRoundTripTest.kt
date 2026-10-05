package com.qaqh.harness.data.ringing

import com.qaqh.harness.data.ringing.model.CommandAck
import com.qaqh.harness.data.ringing.model.CommandEnvelope
import com.qaqh.harness.data.ringing.model.InteractionKinds
import com.qaqh.harness.data.ringing.model.InteractionRequested
import com.qaqh.harness.data.ringing.model.RingingProtocol
import com.qaqh.harness.data.ringing.model.payloadData
import com.qaqh.harness.data.ringing.model.payloadKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.decodeFromJsonElement
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 协议回环：open → renew → bootstrap → SSE 事件 → 审批命令 → ack，
 * MockWebServer 模拟 daemon，JSON 按 qaqh-backend v2 wire 格式手写。
 */
class RingingRoundTripTest {

    private lateinit var server: MockWebServer
    private lateinit var api: RingingApi
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val seed = "sess-1"

    private val sseFrame = buildString {
        append("id: v2:epoch-1:evt-1\n")
        append("event: ringing.event\n")
        append("data: ")
        append(
            """
            {"schema":"qaqh.Ringing","version":2,"server_epoch":"epoch-1","session_id":"sess-1",
             "event_id":"evt-1","delivery":"reliable","cursor":"v2.AAA","ts_ms":1,
             "payload":{"kind":"control_delta","data":{"kind":"interaction_requested","data":{
               "revision":3,"interaction_id":"int_1","call_id":"call_1","kind":"permission",
               "request":{"kind":"inline","data":{"text":"run rm -rf /tmp/x"}}}}}}
            """.trimIndent().replace("\n", "")
        )
        append("\n\n")
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
        scope.cancel()
    }

    private fun json(body: String): MockResponse =
        MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(body).build()

    @Test
    fun `open bootstrap events and permission respond round trip`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url?.encodedPath.orEmpty()
                assertEquals("Bearer test-token", request.headers?.get("Authorization"))
                return when {
                    path.endsWith("/clients/open") && request.method == "POST" -> json(
                        """{"schema":"qaqh.Ringing","version":2,"accepted":true,
                            "client_session_id":"cs-1","server_epoch":"epoch-1",
                            "lease_ttl_ms":30000,"renew_interval_ms":10000,
                            "capabilities":{"subscribe":true,"interact":true,"drive":true,
                                            "timeline":true,"service":true,"content":true,"single_stream":true}}"""
                    )

                    path.endsWith("/leases/renew") -> json("""{"ok":true,"lease_ttl_ms":30000,"renew_interval_ms":10000}""")

                    path == "/ringing/v2/sessions/$seed/bootstrap" -> {
                        assertEquals("cs-1", request.headers?.get("X-QAQH-Client-Session-Id"))
                        json(
                            """{"schema":"qaqh.Ringing","version":2,"server_epoch":"epoch-1","session_id":"$seed",
                                "snapshot_cursor":"v2.SNAP",
                                "control":{"channel":"control","state_revision":3,"snapshot_version":1,
                                           "state":{"session_id":"$seed","activity":"running","revision":3,
                                                    "last_fact_seq":3,"tools":[],"subagents":[],"interactions":[]}},
                                "conversation":{"channel":"conversation","state_revision":0,"snapshot_version":1,"state":{}},
                                "tool":{"channel":"tool","state_revision":0,"snapshot_version":1,"state":{"tools":[]}}}"""
                        )
                    }

                    path == "/ringing/v2/sessions/$seed/events" -> {
                        assertEquals("v2.SNAP", request.url?.queryParameter("since_cursor"))
                        MockResponse.Builder()
                            .code(200)
                            .addHeader("Content-Type", "text/event-stream")
                            .body(sseFrame)
                            .build()
                    }

                    path == "/ringing/v2/commands/tool" -> {
                        val body = request.body?.utf8().orEmpty()
                        assertTrue(body.contains("\"type\":\"tool_permission_respond\""))
                        assertTrue(body.contains("\"tool_call_id\":\"call_1\""))
                        assertTrue(body.contains("\"channel\":\"tool\""))
                        assertTrue(body.contains("\"approved\":true"))
                        json("""{"command_id":"cmd-1","status":"accepted"}""")
                    }

                    else -> MockResponse.Builder().code(404).body("""{"code":"not_found","message":"no route"}""").build()
                }
            }
        }

        api = RingingApi(server.url("/").toString(), "test-token")

        val open = api.open("instance-1")
        assertEquals("cs-1", open.clientSessionId)
        assertEquals("epoch-1", open.serverEpoch)
        assertTrue(open.capabilities!!.subscribe)

        assertTrue(api.renew("cs-1").ok)

        val boot = api.bootstrap("cs-1", seed)
        assertEquals("v2.SNAP", boot.snapshotCursor)
        assertEquals("running", boot.activity)

        // SSE：应收到 interaction_requested(permission) 事件
        val got = ArrayList<RingingApi.SseEvent>()
        val collector = scope.launch { api.events("cs-1", seed, boot.snapshotCursor).collect { got.add(it) } }
        val first = withTimeout(10_000) {
            while (got.isEmpty()) delay(50)
            got.first()
        }
        collector.cancel()
        val ringing = first as RingingApi.SseEvent.Ringing
        assertEquals("control_delta", ringing.envelope.payloadKind)
        val requested = ringing.envelope.payloadData!!["data"]!!.let {
            api.json.decodeFromJsonElement(InteractionRequested.serializer(), it)
        }
        assertEquals(InteractionKinds.PERMISSION, requested.kind)
        assertEquals("call_1", requested.callId)

        // 审批：同意 → 回执
        val ack = api.postCommand(
            "cs-1",
            CommandEnvelope(
                channel = RingingProtocol.CHANNEL_TOOL,
                commandId = "cmd-1",
                clientInstanceId = "instance-1",
                clientSessionId = "cs-1",
                sessionId = seed,
                command = CommandEnvelope.toolPermissionRespond("call_1", approved = true),
            ),
        )
        assertEquals("cmd-1", ack.commandId)
        assertEquals("accepted", ack.status)
    }

    @Test
    fun `unauthorized surfaces as ApiException with plain body`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(401).body("unauthorized").build())
        api = RingingApi(server.url("/").toString(), "bad-token")
        try {
            api.open("instance-1")
            throw AssertionError("expected ApiException")
        } catch (e: RingingApi.ApiException) {
            assertEquals(401, e.httpStatus)
            assertTrue(e.isUnauthorized)
        }
    }

    @Test
    fun `reset_required event is surfaced`() = runBlocking {
        val frame = "event: ringing.reset_required\n" +
            "data: " +
            """{"schema":"qaqh.Ringing","version":2,"server_epoch":"epoch-1",
               "session_id":"sess-1","snapshot_cursor":"v2.SNAP","reason":"cursor_expired"}""".trimIndent().replace("\n", "") +
            "\n\n"
        server.enqueue(
            MockResponse.Builder().code(200).addHeader("Content-Type", "text/event-stream").body(frame).build()
        )
        api = RingingApi(server.url("/").toString(), "test-token")
        val reset = withTimeout(10_000) {
            api.events("cs-1", seed, "v2.OLD").filterIsInstance<RingingApi.SseEvent.Reset>().first()
        }
        assertEquals("cursor_expired", reset.reset.reason)
    }

    @Test
    fun `wire constants match backend`() {
        assertEquals("permission", InteractionKinds.PERMISSION)
        assertEquals("plan", InteractionKinds.PLAN)
        assertEquals("tool", RingingProtocol.CHANNEL_TOOL)
    }
}

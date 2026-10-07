package com.qaqh.harness.data.ringing

import com.qaqh.harness.data.ringing.model.ApprovalChallenge
import com.qaqh.harness.data.ringing.model.ApprovalDecisions
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * device 身份审批 challenge 流：/approvals 快照解析（daemon challenge.rs view 形状）、
 * /approvals/respond 请求体形状、会话未物化瞬态判定（对齐 Rust error.rs）。
 */
class ApprovalChallengeFlowTest {

    private lateinit var server: MockWebServer
    private lateinit var api: RingingApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = RingingApi(server.url("/").toString(), "device-token")
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `challenges snapshot parses daemon view shape`() = runBlocking {
        // 形状 = daemon ChallengeStore.issue_views 的 view()：challenge_id 64hex、
        // kind 三值词表、expires_in 秒、details 为白名单字段（permission）/交互正文（ask/plan）
        server.enqueue(
            MockResponse.Builder().code(200)
                .addHeader("Content-Type", "application/json")
                .body(
                    """{"challenges":[
                        {"challenge_id":"c64a","kind":"tool_permission","expires_in":299,
                         "details":{"tool_name":"exec","action_summary":"rm -rf /tmp/x",
                                    "paths":["/tmp/x"],"category":"fs","level":2,
                                    "risk":"high","consequence":"不可逆删除"}},
                        {"challenge_id":"c64b","kind":"ask","expires_in":120,
                         "details":{"questions":[{"question":"你更喜欢猫还是狗？","options":["猫","狗"]}]}},
                        {"challenge_id":"c64c","kind":"plan","expires_in":300,
                         "details":{"plan":"先删库再跑路"}}
                    ]}"""
                ).build()
        )
        val approvals = api.approvals("cs-1", "sess-1")
        assertEquals(3, approvals.challenges.size)

        val permission = approvals.challenges[0]
        assertEquals("c64a", permission.challengeId)
        assertEquals(ApprovalChallenge.KIND_TOOL_PERMISSION, permission.kind)
        assertEquals(299L, permission.expiresInSeconds)
        val details = permission.permissionDetails
        assertEquals("exec", details?.toolName)
        assertEquals("rm -rf /tmp/x", details?.actionSummary)
        assertEquals(listOf("/tmp/x"), details?.paths)

        // ask 的 details 是交互正文原样：questions 可直接取问题文本
        val ask = approvals.challenges[1]
        assertEquals("c64b", ask.challengeId)
        // 非 permission 形状的 details 也能解出空投影（不炸）：问题文本只能从原始 details 取
        assertNull(ask.permissionDetails?.toolName)
        val questions = (ask.details as JsonObject)["questions"]!!.jsonArray
        assertEquals("你更喜欢猫还是狗？", questions[0].jsonObject["question"]!!.jsonPrimitive.content)

        assertEquals("c64c", approvals.challenges[2].challengeId)
    }

    @Test
    fun `approvalRespond posts opaque challenge id and decision`() = runBlocking {
        server.enqueue(
            MockResponse.Builder().code(200)
                .addHeader("Content-Type", "application/json")
                .body("""{"command_id":"cmd-1","status":"accepted"}""")
                .build()
        )
        val ack = api.approvalRespond("cs-1", "sess-1", "c64a", ApprovalDecisions.APPROVE)
        assertEquals("cmd-1", ack.commandId)
        assertEquals("accepted", ack.status)

        val request = server.takeRequest()
        assertEquals("/ringing/v2/sessions/sess-1/approvals/respond", request.url?.encodedPath)
        assertEquals("Bearer device-token", request.headers?.get("Authorization"))
        val body = request.body?.utf8().orEmpty()
        assertTrue(body.contains("\"challenge_id\":\"c64a\""))
        assertTrue(body.contains("\"decision\":\"approve\""))
    }

    @Test
    fun `session not ready covers 404 409 and codes`() {
        assertTrue(api.run { RingingApi.ApiException(404, null, "nf").isSessionNotReady })
        assertTrue(api.run { RingingApi.ApiException(409, "snapshot_missing", "x").isSessionNotReady })
        // attach 被 command 通道拒绝时没有 HTTP 状态：code 语义等价（对齐 Rust error.rs）
        assertTrue(api.run { RingingApi.ApiException(0, "session_not_found", "x").isSessionNotReady })
        assertFalse(api.run { RingingApi.ApiException(401, "lease_required", "x").isSessionNotReady })
        assertFalse(api.run { RingingApi.ApiException(500, null, "x").isSessionNotReady })
        assertFalse(api.run { RingingApi.ApiException(401, null, "plain unauthorized").isUnauthorized.not() })
    }
}

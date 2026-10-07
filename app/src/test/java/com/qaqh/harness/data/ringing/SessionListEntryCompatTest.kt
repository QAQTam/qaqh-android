package com.qaqh.harness.data.ringing

import com.qaqh.harness.data.ringing.model.Approvals
import com.qaqh.harness.data.ringing.model.ApprovalDecisions
import com.qaqh.harness.data.ringing.model.SessionListEntry
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * backend-fix 前后两代 daemon 的 session.list / approvals wire 形状兼容回归：
 * 新代 `running:bool` → `status` 8 态词表（last_summary 删除，新增 title）；
 * approvals 对 device 身份返回 challenges 数组（admin 仍是 pending_* 双键）。
 */
class SessionListEntryCompatTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Test
    fun `new daemon status vocabulary parses and derives working`() {
        val entry = json.decodeFromString(
            SessionListEntry.serializer(),
            """{"session_id":"s1","status":"working","title":"修复登录页","turn_count":3}""",
        )
        assertEquals("working", entry.runStatus)
        assertTrue(entry.isWorking)
        assertEquals("修复登录页", entry.displayTitle)
        assertFalse(entry.running) // 旧字段缺失，serde 缺键恒 false——判定必须走 runStatus
    }

    @Test
    fun `legacy daemon running bool falls back`() {
        val entry = json.decodeFromString(
            SessionListEntry.serializer(),
            """{"session_id":"s1","running":true,"last_summary":"旧摘要"}""",
        )
        assertTrue(entry.isWorking)
        assertEquals("旧摘要", entry.displayTitle)
    }

    @Test
    fun `legacy idle entry is not working`() {
        val entry = json.decodeFromString(
            SessionListEntry.serializer(),
            """{"session_id":"s1","running":false}""",
        )
        assertEquals("not_running", entry.runStatus)
        assertFalse(entry.isWorking)
    }

    @Test
    fun `waiting states map to runStatus for ui`() {
        val entry = json.decodeFromString(
            SessionListEntry.serializer(),
            """{"session_id":"s1","status":"waiting_permission"}""",
        )
        assertEquals("waiting_permission", entry.runStatus)
        assertFalse(entry.isWorking)
    }

    @Test
    fun `device approvals challenges shape parses`() {
        // challenge_id 实际是 64 hex 随机 token（challenge.rs random_token）
        val id = "ab".repeat(32)
        val body = """{"challenges":[{"challenge_id":"$id",""" +
            """"kind":"tool_permission","expires_in":299,""" +
            """"details":{"tool_name":"exec","action_summary":"rm -rf /tmp","paths":["/tmp"],"level":2}}]}"""
        val approvals = json.decodeFromString(Approvals.serializer(), body)
        assertEquals(1, approvals.challenges.size)
        val challenge = approvals.challenges.single()
        assertEquals(id, challenge.challengeId)
        assertEquals("tool_permission", challenge.kind)
        assertEquals(299L, challenge.expiresInSeconds)
        assertEquals("exec", challenge.permissionDetails?.toolName)
        assertEquals(listOf("/tmp"), challenge.permissionDetails?.paths)
        // admin 形状键在 device 响应中不存在
        assertEquals(null, approvals.pendingPermission)
    }

    @Test
    fun `admin approvals legacy shape ignores challenges key`() {
        val approvals = json.decodeFromString(
            Approvals.serializer(),
            """{"pending_permission":{"tool_call_id":"call_1","tool_name":"exec"}}""",
        )
        assertEquals("call_1", approvals.pendingPermission?.toolCallId)
        assertTrue(approvals.challenges.isEmpty())
        // decision 词表与后端 challenge.rs command_for 对齐（编译期常量，防手滑改名）
        assertEquals("approve", ApprovalDecisions.APPROVE)
        assertEquals("trust", ApprovalDecisions.TRUST)
        assertEquals("submit", ApprovalDecisions.SUBMIT)
        assertEquals("dismiss", ApprovalDecisions.DISMISS)
    }

    @Test
    fun `session list array decodes with unknown keys tolerated`() {
        val list = json.decodeFromString(
            ListSerializer(SessionListEntry.serializer()),
            """[{"session_id":"s1","status":"idle","effort":"high","mode":"auto","skills":[]},
                {"session_id":"s2","running":true}]""",
        )
        assertEquals(2, list.size)
        assertEquals("idle", list[0].runStatus)
        assertTrue(list[1].isWorking)
    }
}

package com.qaqh.harness.ui.home

import com.qaqh.harness.data.ringing.model.SessionListEntry
import com.qaqh.harness.data.ringing.model.WorkspaceEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionGroupingTest {

    private fun entry(id: String, workspaceId: String?, updatedAt: Long) = SessionListEntry(
        sessionId = id,
        updatedAt = updatedAt,
        model = "glm",
        title = "T-$id",
        workspaceId = workspaceId,
    )

    private fun workspace(id: String, order: Int, title: String = "WS-$id") =
        WorkspaceEntry(id = id, path = "/e/proj", title = title, order = order)

    @Test
    fun `groups by workspace with registry order`() {
        val sections = groupSessionsByWorkspace(
            sessions = listOf(
                entry("s1", "wsB", 100),
                entry("s2", "wsA", 200),
                entry("s3", "wsA", 300),
            ),
            workspaces = listOf(workspace("wsA", order = 0), workspace("wsB", order = 1)),
        )
        assertEquals(listOf("wsA", "wsB"), sections.map { it.workspaceId })
        assertEquals(listOf("s3", "s2"), sections[0].sessions.map { it.sessionId })
        assertEquals("WS-wsA", sections[0].title)
    }

    @Test
    fun `dangling workspace id falls back to ungrouped`() {
        val sections = groupSessionsByWorkspace(
            sessions = listOf(entry("s1", "gone", 100), entry("s2", null, 50)),
            workspaces = emptyList(),
        )
        assertEquals(1, sections.size)
        assertEquals(null, sections[0].workspaceId)
        assertEquals("未分组", sections[0].title)
        // s1 的 workspace_id 是死引用，也归未分组；按时间降序
        assertEquals(listOf("s1", "s2"), sections[0].sessions.map { it.sessionId })
    }

    @Test
    fun `empty workspaces produce no ghost section and ungrouped stays first`() {
        val sections = groupSessionsByWorkspace(
            sessions = listOf(entry("s1", null, 100), entry("s2", "wsA", 90)),
            workspaces = listOf(workspace("wsA", 0), workspace("wsEmpty", 1)),
        )
        assertEquals(2, sections.size)
        assertEquals(null, sections[0].workspaceId)
        assertEquals("wsA", sections[1].workspaceId)
    }

    @Test
    fun `relative time buckets`() {
        val now = 1_800_000_000_000L
        assertEquals("刚刚", relativeTimeLabel(now / 1000 - 30, now))
        assertEquals("5 分钟前", relativeTimeLabel(now / 1000 - 300, now))
        assertEquals("2 小时前", relativeTimeLabel(now / 1000 - 2 * 3600, now))
        assertEquals("3 天前", relativeTimeLabel(now / 1000 - 3 * 86_400, now))
        // 未来时间戳（时钟偏差）钳到刚刚
        assertEquals("刚刚", relativeTimeLabel(now / 1000 + 100, now))
        // 更早 → 日期
        assertEquals(relativeTimeLabel(now / 1000 - 30 * 86_400, now), relativeTimeLabel(now / 1000 - 30 * 86_400, now))
    }

    @Test
    fun `waiting states are derived from pending approvals`() {
        fun approval(kind: String) = com.qaqh.harness.connection.PendingApproval(
            interactionId = "i", kind = kind, callId = null, summary = "s", expiresAtMs = null,
        )
        // 交互挂起覆盖执行中（control 投影在 interaction 期间仍报 running）
        assertEquals(ActivityView.WAITING_PERMISSION, activityViewOf(null, "running", listOf(approval("permission"))))
        assertEquals(ActivityView.WAITING_ASK, activityViewOf(null, "running", listOf(approval("ask"))))
        assertEquals(ActivityView.WAITING_PLAN, activityViewOf(null, "running", listOf(approval("plan"))))
        // 多条并存：授权 > 回答 > 评审（需要用户介入的紧急度排序）
        assertEquals(
            ActivityView.WAITING_PERMISSION,
            activityViewOf(null, "running", listOf(approval("plan"), approval("permission"))),
        )
        // 无挂起 → 投影三态映射（含 domain 词表防御性归并）
        assertEquals(ActivityView.WORKING, activityViewOf(null, "running", emptyList()))
        assertEquals(ActivityView.WORKING, activityViewOf(null, "working", emptyList()))
        assertEquals(ActivityView.INTERRUPTED, activityViewOf(null, "interrupted", emptyList()))
        assertEquals(ActivityView.IDLE, activityViewOf(null, "idle", emptyList()))
        assertEquals(ActivityView.IDLE, activityViewOf(null, null, emptyList()))
    }

    @Test
    fun `legacy daemon derives from entry running per session`() {
        val running = SessionListEntry(sessionId = "s1", running = true)
        val stopped = SessionListEntry(sessionId = "s2", running = false)
        // 无 status 键：entry.running 是本会话事实源（旧实现误用全局 activity）
        assertEquals(ActivityView.WORKING, activityViewOf(running, null, emptyList()))
        assertEquals(ActivityView.IDLE, activityViewOf(stopped, null, emptyList()))
        // 当前挂接会话的 live 仍可补轮询空窗
        assertEquals(ActivityView.WORKING, activityViewOf(stopped, "running", emptyList()))
    }

    @Test
    fun `run status vocabulary maps to views`() {
        assertEquals(ActivityView.NOT_RUNNING, runStatusToView("not_running"))
        assertEquals(ActivityView.IDLE, runStatusToView("idle"))
        assertEquals(ActivityView.WORKING, runStatusToView("working"))
        assertEquals(ActivityView.WAITING_PERMISSION, runStatusToView("waiting_permission"))
        assertEquals(ActivityView.WAITING_ASK, runStatusToView("waiting_ask"))
        assertEquals(ActivityView.WAITING_PLAN, runStatusToView("waiting_plan"))
        assertEquals(ActivityView.CANCELED, runStatusToView("canceled"))
        assertEquals(ActivityView.ERROR, runStatusToView("error"))
        // 未知词 → null，调用方退本地推导
        assertEquals(null, runStatusToView("quantum_state"))
        assertEquals(null, runStatusToView(null))
    }

    @Test
    fun `new daemon status is authoritative for non-current sessions`() {
        fun s(status: String) = SessionListEntry(sessionId = "x", status = status)
        // not_running 不得渲染成空闲（G2 裁决：未加载 ≠ idle）
        assertEquals(ActivityView.NOT_RUNNING, activityViewOf(s("not_running"), null, emptyList()))
        // canceled/error 是驻留终态，直接上屏（旧 daemon 拿不到的语义）
        assertEquals(ActivityView.CANCELED, activityViewOf(s("canceled"), null, emptyList()))
        assertEquals(ActivityView.ERROR, activityViewOf(s("error"), null, emptyList()))
        // 后端 status 自带 waiting_*，无需本地 pending 推导
        assertEquals(ActivityView.WAITING_PERMISSION, activityViewOf(s("waiting_permission"), null, emptyList()))
        // 未知词退本地推导：running=true 仍可点亮
        assertEquals(
            ActivityView.WORKING,
            activityViewOf(SessionListEntry(sessionId = "x", status = "quantum_state", running = true), null, emptyList()),
        )
    }

    @Test
    fun `live sources override status only within polling lag`() {
        val idle = SessionListEntry(sessionId = "x", status = "idle")
        val working = SessionListEntry(sessionId = "x", status = "working")
        val waiting = SessionListEntry(sessionId = "x", status = "waiting_ask")
        val notRunning = SessionListEntry(sessionId = "x", status = "not_running")
        fun approval(kind: String) = com.qaqh.harness.connection.PendingApproval(
            interactionId = "i", kind = kind, callId = null, summary = "s", expiresAtMs = null,
        )
        // 回合先于轮询开始：live working 升 idle / not_running / 驻留终态
        assertEquals(ActivityView.WORKING, activityViewOf(idle, "running", emptyList()))
        assertEquals(ActivityView.WORKING, activityViewOf(notRunning, "running", emptyList()))
        assertEquals(
            ActivityView.WORKING,
            activityViewOf(SessionListEntry(sessionId = "x", status = "canceled"), "working", emptyList()),
        )
        // live pending 盖过轮询里的 waiting/working
        assertEquals(ActivityView.WAITING_ASK, activityViewOf(working, null, listOf(approval("ask"))))
        assertEquals(ActivityView.WAITING_PERMISSION, activityViewOf(waiting, "running", listOf(approval("permission"))))
        // waiting 态不被 live working 降级（挂起交互发生在回合中途）
        assertEquals(ActivityView.WAITING_ASK, activityViewOf(waiting, "running", emptyList()))
        // interrupted 覆盖非等待、非 not_running 的 status
        assertEquals(ActivityView.INTERRUPTED, activityViewOf(working, "interrupted", emptyList()))
        assertEquals(ActivityView.NOT_RUNNING, activityViewOf(notRunning, "interrupted", emptyList()))
    }
}

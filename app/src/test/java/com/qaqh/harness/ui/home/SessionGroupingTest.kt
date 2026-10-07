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
}

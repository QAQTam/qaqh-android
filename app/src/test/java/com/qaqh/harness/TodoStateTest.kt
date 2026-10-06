package com.qaqh.harness

import com.qaqh.harness.data.ringing.model.TodoState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** todo_write / todo_update 契约解析（对齐 TUI TodoBlock 语义）。 */
class TodoStateTest {

    @Test
    fun `write parses items and replaces whole list`() {
        val state = TodoState.parseWrite(
            """{"items":[
                {"id":"1","title":"读代码","status":"completed"},
                {"id":"2","title":"改 wire","status":"in_progress","evidence":"tls.rs"},
                {"title":"跑冒烟","status":"pending"}
            ]}""",
            previous = null,
        )!!
        assertEquals(3, state.total)
        assertEquals("T1", state.items[0].id)
        assertEquals("T2", state.items[1].id)
        assertEquals("T3", state.items[2].id) // 无 id 顺序分配
        assertEquals(1, state.completed)
        assertEquals("改 wire", state.inProgress?.title)
        assertEquals("tls.rs", state.items[1].evidence)
    }

    @Test
    fun `malformed write is rejected entirely`() {
        assertNull(TodoState.parseWrite("""{"items":[]}""", null)) // 空 items
        assertNull(TodoState.parseWrite("""{"items":[{"status":"pending"}]}""", null)) // 无标题无 id
        assertNull(TodoState.parseWrite("""{"items":[{"title":"x","status":"done"}]}""", null)) // 非法状态
        assertNull(TodoState.parseWrite("""not json""", null))
        assertNull(TodoState.parseWrite(null, null))
    }

    @Test
    fun `empty title with existing id reuses previous title`() {
        val prev = TodoState.parseWrite("""{"items":[{"id":"1","title":"旧标题","status":"pending"}]}""", null)!!
        val next = TodoState.parseWrite("""{"items":[{"id":"1","title":"","status":"completed"}]}""", prev)!!
        assertEquals("旧标题", next.items[0].title)
        assertEquals("completed", statusOf(next, "T1"))
    }

    @Test
    fun `update folds delta by id and ignores unknown id`() {
        var state = TodoState.parseWrite(
            """{"items":[{"id":"T1","title":"a","status":"pending"},{"id":"T2","title":"b","status":"pending"}]}""",
            null,
        )!!
        state = TodoState.parseUpdate("""{"id":"2","status":"in_progress"}""", state)!! // 裸数字 id 归一 T2
        assertEquals("in_progress", statusOf(state, "T2"))
        assertNull(TodoState.parseUpdate("""{"id":"T9","status":"completed"}""", state)) // 未知 id 忽略
        assertNull(TodoState.parseUpdate("""{"id":"T1","status":"weird"}""", state)) // 非法状态
    }

    @Test
    fun `ordering is in-progress then pending then completed`() {
        val state = TodoState.parseWrite(
            """{"items":[
                {"id":"1","title":"done","status":"completed"},
                {"id":"2","title":"doing","status":"in_progress"},
                {"id":"3","title":"todo","status":"pending"}
            ]}""",
            null,
        )!!
        assertEquals(listOf("doing", "todo", "done"), state.ordered().map { it.title })
    }

    private fun statusOf(state: TodoState, id: String): String =
        state.items.first { it.id == id }.status.wire
}

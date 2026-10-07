package com.qaqh.harness.data.ringing.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 待办清单状态（数据源 = timeline 里 todo 工具块的 args_json，无专门 SSE 流）。
 * 契约对齐 TUI transcript.rs TodoBlock（实测会话日志 + 后端 split.rs）：
 * - `todo_write`：`{"items":[{id?,title,status,description?,evidence?}]}` 整表替换；
 *   title 缺省且带既有 id 合法（沿用旧标题）；任一条目畸形整体放弃（半个计划比没有计划误导）。
 * - `todo_update`：`{id,status,evidence?}` 单条 delta；id 未知则忽略（宁少改不错改）。
 * - 状态只有 pending / in_progress / completed 三态。
 */
enum class TodoStatus(val wire: String) {
    PENDING("pending"),
    IN_PROGRESS("in_progress"),
    COMPLETED("completed");

    companion object {
        fun fromWire(value: String?): TodoStatus? = entries.firstOrNull { it.wire == value }
    }
}

data class TodoItem(
    val id: String,
    val title: String,
    val status: TodoStatus,
    val evidence: String? = null,
)

data class TodoState(val items: List<TodoItem>) {
    val completed: Int get() = items.count { it.status == TodoStatus.COMPLETED }
    val total: Int get() = items.size
    val allDone: Boolean get() = items.isNotEmpty() && completed == items.size
    val inProgress: TodoItem? get() = items.firstOrNull { it.status == TodoStatus.IN_PROGRESS }

    /** 渲染顺序：进行中 → 待办 → 已完成（稳定排序，同状态保持原序）。 */
    fun ordered(): List<TodoItem> = items.sortedBy {
        when (it.status) {
            TodoStatus.IN_PROGRESS -> 0
            TodoStatus.PENDING -> 1
            TodoStatus.COMPLETED -> 2
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val TODO_TOOLS = setOf("todo_write", "todo_update", "todo_list")

        fun isTodoTool(name: String?): Boolean = name?.lowercase() in TODO_TOOLS

        /**
         * timeline 工具块帧 → 待办状态折叠（唯一入口，聊天屏 reducer 与全局实况通知跟踪器共用）：
         * todo_write/todo_list 整表替换、todo_update 单条 delta；非 todo 工具或畸形帧原样返回。
         */
        fun ingestTool(state: TodoState?, toolName: String?, argsJson: String?): TodoState? {
            if (!isTodoTool(toolName)) return state
            return when (toolName?.lowercase()) {
                "todo_update" -> state?.let { parseUpdate(argsJson, it) ?: it }
                else -> parseWrite(argsJson, state) ?: state
            }
        }

        /** todo_write / todo_list：整表替换；畸形返回 null。 */
        fun parseWrite(argsJson: String?, previous: TodoState?): TodoState? {
            if (argsJson.isNullOrBlank()) return null
            val root = runCatching { json.parseToJsonElement(argsJson) }.getOrNull() as? JsonObject ?: return null
            val arr = root["items"] as? JsonArray ?: return null
            if (arr.isEmpty()) return null
            val items = mutableListOf<TodoItem>()
            arr.forEachIndexed { index, element ->
                val o = element as? JsonObject ?: return null
                val idRaw = (o["id"] as? JsonPrimitive)?.content?.trim()
                val title = (o["title"] as? JsonPrimitive)?.content?.trim().orEmpty()
                val status = TodoStatus.fromWire((o["status"] as? JsonPrimitive)?.content) ?: return null
                // 既没标题又没 id：指代不了任何东西，整体回退
                if (title.isEmpty() && idRaw.isNullOrEmpty()) return null
                val id = normalizeId(idRaw, index)
                // 带既有 id 且本次 title 为空：沿用上一版标题（后端沿用语义）
                val effectiveTitle = title.ifEmpty {
                    previous?.items?.firstOrNull { it.id == id }?.title ?: id
                }
                val evidence = (o["evidence"] as? JsonPrimitive)?.content?.trim()?.ifEmpty { null }
                items += TodoItem(id, effectiveTitle, status, evidence)
            }
            return TodoState(items)
        }

        /** todo_update：单条 delta；id 未知/畸形返回 null（面板维持上一状态）。 */
        fun parseUpdate(argsJson: String?, state: TodoState): TodoState? {
            if (argsJson.isNullOrBlank()) return null
            val o = runCatching { json.parseToJsonElement(argsJson) }.getOrNull() as? JsonObject ?: return null
            val idRaw = (o["id"] as? JsonPrimitive)?.content?.trim()
            if (idRaw.isNullOrEmpty()) return null
            val status = TodoStatus.fromWire((o["status"] as? JsonPrimitive)?.content) ?: return null
            val id = normalizeId(idRaw, -1)
            if (state.items.none { it.id == id }) return null
            val evidence = (o["evidence"] as? JsonPrimitive)?.content?.trim()?.ifEmpty { null }
            return state.copy(
                items = state.items.map {
                    if (it.id != id) it
                    else it.copy(status = status, evidence = evidence ?: it.evidence)
                }
            )
        }

        /** id 归一（TUI 语义）："1" / 1 / "T1" → "T1"；非数字 id 原样。 */
        private fun normalizeId(raw: String?, index: Int): String {
            val t = raw?.trim()
            if (t.isNullOrEmpty()) return "T${index + 1}"
            val digits = t.removePrefix("T").removePrefix("t")
            return if (digits.isNotEmpty() && digits.all { it.isDigit() }) "T$digits" else t
        }
    }
}

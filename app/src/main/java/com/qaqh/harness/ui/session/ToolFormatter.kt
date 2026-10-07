package com.qaqh.harness.ui.session

import com.qaqh.harness.data.ringing.model.TimelineTool
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 工具卡片数据清洗（对齐 TUI ui/v2/adapter.rs + transcript.rs 的策略）：
 * - 标题 = 动词 + typed display.header 正文（shell.command / path / query）；
 *   header 缺席回退 summary，但 JSON 形摘要一律拒收（isJsonLike）；
 *   **永远不用 args_json 做标题**（todo 家族除外，尚未实装）。
 * - 正文 = typed body（shell.output/text/streams）→ output → progress；
 *   清洗管线 = 剥 ANSI → CRLF/\r 覆盖折叠 → 剔控制字符（保留 \n \t）。
 * - 失败：头部 "失败 (exit N)"，理由全文只出现在正文一次。
 */
object ToolFormatter {

    // ---------- 清洗管线（TUI sanitize_text / normalize_cr 等价物） ----------

    private val ANSI = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]|\u001B[@-_]")

    /** 剥 ANSI 转义 → CRLF/\r 覆盖折叠（行内取最后非空段，apt/spinner 行）→ 剔控制字符。 */
    fun sanitize(text: String): String {
        var s = text.replace(ANSI, "")
        s = s.split('\n').joinToString("\n") { line ->
            if ('\r' !in line) line else line.split('\r').lastOrNull { it.isNotBlank() } ?: ""
        }
        return s.filter { it == '\n' || it == '\t' || !it.isISOControl() }
    }

    /** 摘要防 JSON 污染：命中 `{`/`[` 前缀即判为 JSON 形，拒收（TUI is_json_like_summary）。 */
    fun isJsonLike(text: String?): Boolean {
        val t = text?.trim() ?: return true
        return t.isEmpty() || t.startsWith("{") || t.startsWith("[")
    }

    // ---------- 标题：动词 + 正文 ----------

    data class Header(val verb: String, val rest: String?, val failed: Boolean, val exitCode: Int?)

    fun header(tool: TimelineTool): Header {
        val failed = tool.state == "failed" || tool.failure != null
        val display = tool.display
        val h = display?.header
        val typedRest = when {
            h == null -> null
            h.str("kind") == "shell" -> h.str("command")
            h.str("kind") == "path" -> shortenPath(h.str("path") ?: "")
            h.str("kind") == "query" -> listOfNotNull(h.str("query"), h.str("scope")?.let { " · $it" }).joinToString("")
            else -> h.str("label")
        }?.takeIf { it.isNotBlank() }

        // 摘要回退链：display.summary → tool.summary，JSON 形拒收
        val summaryRest = listOfNotNull(display?.summary, tool.summary)
            .firstOrNull { !isJsonLike(it) }

        return Header(
            verb = verb(tool, h),
            rest = (typedRest ?: summaryRest)?.let(::sanitize)?.replace('\n', ' ')?.trim(),
            failed = failed,
            exitCode = tool.exitCode ?: typedBodyField(tool, "exit_code"),
        )
    }

    private fun verb(tool: TimelineTool, h: JsonObject?): String {
        val failed = tool.state == "failed" || tool.failure != null
        if (failed) return "失败"
        if (tool.state == "cancelled") return "已取消"
        if (tool.state == "backgrounded") return "已转后台"
        val op = h?.str("kind")?.let { if (it == "path") h.str("op") else null }
        return when (op) {
            "edit" -> "编辑"
            "write" -> "写入"
            "patch" -> "补丁"
            "delete" -> "删除"
            else -> verbByFamily(tool.name?.lowercase())
        }
    }

    private fun verbByFamily(name: String?): String = when (name) {
        "exec", "shell", "bash", "run" -> "运行"
        "read" -> "读取"
        "grep", "search" -> "搜索"
        "glob", "ls", "find" -> "查找"
        "write" -> "写入"
        "edit", "apply_patch" -> "编辑"
        "todo_write", "todo_update", "todo_list" -> "更新计划"
        // 工具系统现代化后 skills 拆分为三个独立工具（qaqh-tool-core tool_capabilities）
        "skill_activate" -> "启用技能"
        "skill_list" -> "列出技能"
        "skill_resource" -> "读取技能资源"
        else -> name?.replace('_', ' ')?.replaceFirstChar { it.uppercase() } ?: "工具"
    }

    /** 头部路径缩短：长 token 中段省略（保留头 1/3 + 尾 2/3，头见树尾见文件名；TUI elide_middle）。 */
    fun shortenPath(path: String, budget: Int = 48): String {
        val p = path.replace('\\', '/')
        val home = System.getProperty("user.home")
        val shortened = if (!home.isNullOrBlank() && p.startsWith(home)) "~" + p.substring(home.length) else p
        if (shortened.length <= budget) return shortened
        val head = shortened.substring(0, budget / 3)
        val tail = shortened.substring(shortened.length - budget * 2 / 3)
        return "$head…$tail"
    }

    // ---------- 正文 ----------

    /** typed body → output → progress；结果已 sanitize。 */
    fun body(tool: TimelineTool): String? {
        val b = tool.display?.body
        val typed = when (b?.str("kind")) {
            "shell" -> b.str("output")
            "text" -> b.str("text")
            "streams" -> listOfNotNull(
                b.str("stdout")?.takeIf { it.isNotBlank() },
                b.str("stderr")?.takeIf { it.isNotBlank() }?.let { "[stderr]\n$it" },
            ).joinToString("\n").takeIf { it.isNotBlank() }
            else -> null
        }
        val raw = typed
            ?: tool.output?.takeIf { it.isNotBlank() }
            ?: tool.progress.takeIf { it.isNotBlank() }
            ?: return null
        return sanitize(raw)
    }

    /** 折叠预览：流式/输出取尾部（结论在最后），返回 (可见行, 隐藏行数)。 */
    fun previewTail(text: String, maxLines: Int = 3): Pair<List<String>, Int> {
        val lines = text.split('\n').filter { it.isNotBlank() }
        if (lines.size <= maxLines) return lines to 0
        return lines.takeLast(maxLines) to (lines.size - maxLines)
    }

    fun elapsedLabel(tool: TimelineTool): String? {
        val ms = tool.display?.metrics?.elapsedMs ?: return null
        return when {
            ms < 10_000 -> "%.1fs".format(ms / 1000.0)
            ms < 60_000 -> "${ms / 1000}s"
            else -> "${ms / 60_000}m${String.format("%02d", ms % 60_000 / 1000)}s"
        }
    }

    /** 统一 diff（文件变更类工具）：顶层 diff 或 typed body 的 unified。 */
    fun diff(tool: TimelineTool): String? {
        tool.diff?.takeIf { it.isNotBlank() }?.let { return it }
        val b = tool.display?.body ?: return null
        return if (b.str("kind") == "diff") b.str("unified") else null
    }

    private fun typedBodyField(tool: TimelineTool, key: String): Int? =
        (tool.display?.body?.get(key) as? JsonPrimitive)?.content?.toIntOrNull()

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.content
}

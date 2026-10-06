package com.qaqh.harness.data.ringing.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Timeline API（`GET /sessions/{session_id}/timeline`）wire 模型，
 * 字段对齐 qaqh-domain timeline.rs 与 webui/src/api/qaqh/Timeline*.ts。
 */

@Serializable
data class TimelineResponse(
    val snapshot: TimelineSnapshot,
    @SerialName("has_more") val hasMore: Boolean = false,
    @SerialName("total_turns") val totalTurns: Int = 0,
)

@Serializable
data class TimelineSnapshot(
    val watermark: Long = 0,
    val turns: List<TimelineTurn> = emptyList(),
)

@Serializable
data class TimelineTurn(
    @SerialName("turn_id") val turnId: String,
    @SerialName("turn_index") val turnIndex: Long = 0,
    @SerialName("created_seq") val createdSeq: Long = 0,
    @SerialName("user_text") val userText: String? = null,
    val sealed: Boolean = false,
    val state: String? = null, // running | completed | failed | cancelled
    val rounds: List<TimelineRound> = emptyList(),
)

@Serializable
data class TimelineRound(
    @SerialName("round_num") val roundNum: Int = 0,
    val sealed: Boolean = false,
    @SerialName("is_final") val isFinal: Boolean = false,
    val blocks: List<TimelineBlock> = emptyList(),
)

@Serializable
data class TimelineBlock(
    @SerialName("block_id") val blockId: String,
    @SerialName("block_order") val blockOrder: Int = 0,
    val kind: String, // reasoning | text | tool | notice
    val state: String = "open", // open | sealed
    val text: String? = null,
    val tool: TimelineTool? = null,
)

@Serializable
data class TimelineTool(
    @SerialName("tool_call_id") val toolCallId: String? = null,
    val name: String? = null,
    val state: String? = null, // prepared | running | succeeded | failed | cancelled | backgrounded
    val summary: String? = null,
    @SerialName("args_json") val argsJson: String? = null,
    val output: String? = null,
    @SerialName("exit_code") val exitCode: Int? = null,
    val diff: String? = null,
    /** 运行中流式输出尾（ToolProgress 增量在此累积，本地截尾保后 8K；对齐 TUI MAX_PROGRESS_LEN）。 */
    val progress: String = "",
    @SerialName("progress_truncated") val progressTruncated: Boolean = false,
    /**
     * 类型化展示投影（对齐 TUI adapter）：header/body 是 raw JSON（tag="kind" 的枚举），
     * 展示层按 kind 解析；未知变体回退 legacy 字段（H16 原则，不丢整块）。
     */
    val display: ToolDisplay? = null,
    val failure: ToolFailure? = null,
)

@Serializable
data class ToolDisplay(
    val summary: String? = null,
    val diff: String? = null,
    @SerialName("lines_added") val linesAdded: Int = 0,
    @SerialName("lines_removed") val linesRemoved: Int = 0,
    val header: JsonObject? = null,
    val body: JsonObject? = null,
    val metrics: ToolMetrics? = null,
)

@Serializable
data class ToolMetrics(
    @SerialName("elapsed_ms") val elapsedMs: Long? = null,
    @SerialName("output_bytes") val outputBytes: Long? = null,
    @SerialName("retry_count") val retryCount: Int? = null,
)

@Serializable
data class ToolFailure(val code: String? = null, val message: String? = null)

// ---------- timeline/events SSE ----------

/** `event: timeline.entry` 的完整 data：{schema,...,entry:{timeline_seq, turn_id, round_num?, event}}。 */
@Serializable
data class TimelineEntryEnvelope(
    val schema: String = "",
    @SerialName("session_id") val sessionId: String = "",
    val entry: TimelineEntryFrame,
)

/** TimelineEntry：{timeline_seq, turn_id, round_num?, event}。 */
@Serializable
data class TimelineEntryFrame(
    @SerialName("timeline_seq") val timelineSeq: Long,
    @SerialName("turn_id") val turnId: String? = null,
    @SerialName("round_num") val roundNum: Int? = null,
    val event: JsonElement,
) {
    /** TimelineEvent 是 tag="type" 内嵌标记；取出 type 与整个 data。 */
    val type: String?
        get() = (event as? kotlinx.serialization.json.JsonObject)?.get("type")
            ?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content

    val data: JsonElement?
        get() = event
}

// ---------- driver ----------

@Serializable
data class DriverClaimResponse(
    val accepted: Boolean = false,
    val holder: String? = null,
    @SerialName("driver_epoch") val driverEpoch: Long = 0,
    val reason: String? = null,
)

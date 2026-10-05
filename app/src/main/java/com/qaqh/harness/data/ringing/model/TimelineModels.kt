package com.qaqh.harness.data.ringing.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.JsonElement

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
    @SerialName("completed_at_ms") val completedAtMs: Long? = null,
)

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

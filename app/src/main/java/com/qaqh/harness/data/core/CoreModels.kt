package com.qaqh.harness.data.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * RingingV3 契约的 Kotlin 消费模型(qaqh-ringing-contract 的只读投影)。
 *
 * 传输知识(lease/重连/SSE/TLS pin)在 Rust core(qaqh-client/qaqh-ffi),这里
 * 只有数据形状:bootstrap 快照 + 单流 20 变体事件词表。字段与 serde 定义逐一对齐
 * (events.rs / state.rs / fact/types.rs),未知键忽略、缺键走默认——契约演进时
 * 这里只做加法。
 */

val CoreJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = false
}

// ---------- JSON 小工具 ----------

internal fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.content

internal fun JsonObject.long(key: String): Long? =
    (this[key] as? JsonPrimitive)?.content?.toLongOrNull()

internal fun JsonObject.bool(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

// ---------- bootstrap ----------

/** `GET /ringing/v2/sessions/{id}/bootstrap`(SessionBootstrap)。 */
@Serializable
data class SessionBootstrap(
    val schema: String = "",
    val version: Int = 0,
    @SerialName("session_contract_revision") val sessionContractRevision: String = "",
    @SerialName("server_epoch") val serverEpoch: String = "",
    @SerialName("session_id") val sessionId: String = "",
    val state: SessionState = SessionState(),
)

/**
 * 单会话状态唯一权威快照。`seq` 是**下一个**将分配的事件序号(exclusive 水位):
 * 快照覆盖全部 `seq < state.seq` 的帧,客户端跳过这些、处理 `seq >= state.seq`。
 */
@Serializable
data class SessionState(
    @SerialName("session_id") val sessionId: String? = null,
    val seq: Long = 0,
    /** idle | running | waiting_user | cancelling(ActivityState)。UI 直接按字符串渲染。 */
    val activity: String = "idle",
    @SerialName("current_turn_id") val currentTurnId: String? = null,
    val title: String? = null,
    val turns: List<TurnView> = emptyList(),
    val timeline: List<JsonElement> = emptyList(),
    val tools: List<ToolCallView> = emptyList(),
    val interactions: List<InteractionView> = emptyList(),
    @SerialName("exec_sessions") val execSessions: List<JsonElement> = emptyList(),
    val driver: DriverState? = null,
)

// ---------- 回合 ----------

@Serializable
data class TurnView(
    @SerialName("turn_id") val turnId: String,
    @SerialName("input_id") val inputId: String? = null,
    val mode: String? = null,
    /** running | waiting_user | cancelling(非终态;终态只走 outcome)。 */
    val phase: String? = null,
    val outcome: JsonObject? = null,
) {
    /** finished→completed/failed/cancelled;interrupted→interrupted。 */
    val stateLabel: String?
        get() = outcome?.str("kind")?.let { kind ->
            when (kind) {
                "interrupted" -> "interrupted"
                else -> outcome.obj("data")?.str("terminal") ?: "completed"
            }
        }
}

// ---------- 工具调用 ----------

@Serializable
data class ToolCallView(
    @SerialName("call_id") val callId: String,
    @SerialName("turn_id") val turnId: String? = null,
    @SerialName("tool_name") val toolName: String? = null,
    val args: JsonElement? = null,
    val state: JsonObject,
    @SerialName("exec_session_id") val execSessionId: String? = null,
    val output: JsonObject? = null,
    @SerialName("output_truncated") val outputTruncated: Boolean = false,
    val metrics: JsonObject? = null,
    @SerialName("code_delta") val codeDelta: JsonObject? = null,
) {
    /** state 是 tag="kind" 联合:declared/awaiting_interaction/running/cancelling/finished。 */
    val stateKind: String get() = state.str("kind") ?: "declared"

    /** 终态 outcome(kind=finished 的 data.outcome),非终态为 null。 */
    val finishedOutcome: JsonObject?
        get() = if (stateKind == "finished") state.obj("data")?.obj("outcome") else null
}

// ---------- 交互(审批展示源) ----------

@Serializable
data class InteractionView(
    @SerialName("interaction_id") val interactionId: String,
    @SerialName("call_id") val callId: String? = null,
    @SerialName("turn_id") val turnId: String? = null,
    val kind: String,
    val request: JsonObject,
    val choices: List<JsonElement> = emptyList(),
    @SerialName("expires_at_ms") val expiresAtMs: Long? = null,
    val resolution: JsonObject? = null,
)

@Serializable
data class DriverState(
    val holder: String? = null,
    val epoch: Long = 0,
)

// ---------- ContentValue(tag="kind" content="data" 邻接标记,kotlinx 不支持 → 手解) ----------

sealed interface ContentValue {
    data class Inline(val text: String) : ContentValue
    data class Ref(val contentRef: String) : ContentValue
    data object Unavailable : ContentValue
}

internal fun JsonObject.toContentValue(): ContentValue? = when (str("kind")) {
    "inline" -> obj("data")?.str("text")?.let { ContentValue.Inline(it) }
    "ref" -> obj("data")?.str("content_ref")?.let { ContentValue.Ref(it) }
    "unavailable" -> ContentValue.Unavailable
    else -> null
}

// ---------- SessionEvent(tag="type" 内嵌标记,与 kotlinx classDiscriminator 同形) ----------

/**
 * 单流事件词表(events.rs,tag="type" snake_case)。exec/subagent 载荷对手机 UI
 * 尚无渲染需求,保守收作 JsonElement(解析不失败、后续要渲染再细化)。
 */
@Serializable
sealed interface SessionEvent {
    @Serializable
    @SerialName("activity_changed")
    data class ActivityChanged(
        val activity: String,
        @SerialName("current_turn_id") val currentTurnId: String? = null,
    ) : SessionEvent

    @Serializable
    @SerialName("turn_started")
    data class TurnStarted(
        @SerialName("turn_id") val turnId: String,
        @SerialName("input_id") val inputId: String? = null,
        val mode: String? = null,
    ) : SessionEvent

    @Serializable
    @SerialName("turn_phase_changed")
    data class TurnPhaseChanged(
        @SerialName("turn_id") val turnId: String,
        val phase: String,
    ) : SessionEvent

    @Serializable
    @SerialName("turn_ended")
    data class TurnEnded(
        @SerialName("turn_id") val turnId: String,
        val outcome: JsonObject,
    ) : SessionEvent

    @Serializable
    @SerialName("tool_arguments_delta")
    data class ToolArgumentsDelta(
        @SerialName("call_id") val callId: String,
        @SerialName("turn_id") val turnId: String,
        @SerialName("tool_name") val toolName: String,
        val text: String,
    ) : SessionEvent

    @Serializable
    @SerialName("tool_declared")
    data class ToolDeclared(
        @SerialName("call_id") val callId: String,
        @SerialName("turn_id") val turnId: String,
        @SerialName("tool_name") val toolName: String,
        val args: JsonElement? = null,
    ) : SessionEvent

    @Serializable
    @SerialName("tool_state_changed")
    data class ToolStateChanged(
        @SerialName("call_id") val callId: String,
        @SerialName("turn_id") val turnId: String,
        val state: JsonObject,
    ) : SessionEvent

    @Serializable
    @SerialName("tool_output_delta")
    data class ToolOutputDelta(
        @SerialName("call_id") val callId: String,
        val stream: String,
        val text: String,
    ) : SessionEvent

    @Serializable
    @SerialName("tool_finished")
    data class ToolFinished(
        @SerialName("call_id") val callId: String,
        val outcome: JsonObject,
        @SerialName("exec_session_id") val execSessionId: String? = null,
        val output: JsonObject? = null,
        @SerialName("output_truncated") val outputTruncated: Boolean = false,
        val metrics: JsonObject? = null,
        @SerialName("code_delta") val codeDelta: JsonObject? = null,
    ) : SessionEvent

    @Serializable
    @SerialName("exec_session_started")
    data class ExecSessionStarted(val session: JsonElement) : SessionEvent

    @Serializable
    @SerialName("exec_session_state_changed")
    data class ExecSessionStateChanged(val session: JsonElement) : SessionEvent

    @Serializable
    @SerialName("exec_session_output_delta")
    data class ExecSessionOutputDelta(
        @SerialName("exec_session_id") val execSessionId: String,
        @SerialName("start_offset") val startOffset: Long,
        @SerialName("end_offset") val endOffset: Long,
        val source: String,
        val text: String,
    ) : SessionEvent

    @Serializable
    @SerialName("exec_session_finished")
    data class ExecSessionFinished(val session: JsonElement) : SessionEvent

    @Serializable
    @SerialName("timeline_appended")
    data class TimelineAppended(val entry: JsonObject) : SessionEvent

    @Serializable
    @SerialName("message_delta")
    data class MessageDelta(
        @SerialName("turn_id") val turnId: String,
        @SerialName("block_id") val blockId: String,
        @SerialName("block_kind") val blockKind: String,
        val text: String,
    ) : SessionEvent

    @Serializable
    @SerialName("interaction_requested")
    data class InteractionRequested(val interaction: InteractionView) : SessionEvent

    @Serializable
    @SerialName("interaction_resolved")
    data class InteractionResolved(
        @SerialName("interaction_id") val interactionId: String,
        val decision: JsonObject? = null,
        val status: String,
    ) : SessionEvent

    @Serializable
    @SerialName("subagent_updated")
    data class SubagentUpdated(val subagent: JsonElement) : SessionEvent

    @Serializable
    @SerialName("session_meta_changed")
    data class SessionMetaChanged(val title: String? = null) : SessionEvent

    @Serializable
    @SerialName("driver_changed")
    data class DriverChanged(val driver: DriverState? = null) : SessionEvent
}

/**
 * SSE 帧载荷:`seq` 与事件本体在同一层 JSON(serde flatten)。
 * 事件解析失败返回 null(未知新变体跳过——契约允许客户端落后)。
 */
fun parseSessionEventFrame(json: String): Pair<Long, SessionEvent>? {
    val root = runCatching { CoreJson.parseToJsonElement(json) }.getOrNull() as? JsonObject ?: return null
    val seq = root.long("seq") ?: return null
    val event = runCatching { CoreJson.decodeFromJsonElement<SessionEvent>(root) }.getOrNull() ?: return null
    return seq to event
}

/** 纯事件 JSON(Rust 侧 seq 单独传)→ 事件;未知变体返回 null。 */
fun parseSessionEvent(json: String): SessionEvent? =
    runCatching {
        CoreJson.decodeFromJsonElement<SessionEvent>(CoreJson.parseToJsonElement(json))
    }.getOrNull()

// ---------- timeline 条目(kind="kind" content="data" → 手解) ----------

sealed interface TimelineEntryVm {
    val blockId: String?

    data class Input(
        override val blockId: String,
        val turnId: String?,
        val content: ContentValue,
    ) : TimelineEntryVm

    data class AssistantBlock(
        override val blockId: String,
        val turnId: String,
        val blockKind: String,
        val content: ContentValue,
        val model: String?,
    ) : TimelineEntryVm

    data class ToolCall(
        val callId: String,
        val turnId: String?,
    ) : TimelineEntryVm {
        override val blockId: String? get() = null
    }

    data class ToolResult(
        val callId: String,
        val turnId: String?,
        val content: ContentValue,
    ) : TimelineEntryVm {
        override val blockId: String? get() = null
    }

    data class Compaction(val summary: ContentValue) : TimelineEntryVm {
        override val blockId: String? get() = null
    }
}

internal fun JsonObject.toTimelineEntry(): TimelineEntryVm? = when (str("kind")) {
    "input" -> obj("data")?.let { d ->
        d.str("block_id")?.let { bid ->
            TimelineEntryVm.Input(bid, d.str("turn_id"), d.obj("content")?.toContentValue() ?: return null)
        }
    }
    "assistant_block" -> obj("data")?.let { d ->
        d.str("block_id")?.let { bid ->
            TimelineEntryVm.AssistantBlock(
                blockId = bid,
                turnId = d.str("turn_id") ?: return null,
                blockKind = d.str("block_kind") ?: "answer",
                content = d.obj("content")?.toContentValue() ?: return null,
                model = d.str("model"),
            )
        }
    }
    "tool_call" -> obj("data")?.let { d ->
        d.str("call_id")?.let { TimelineEntryVm.ToolCall(it, d.str("turn_id")) }
    }
    "tool_result" -> obj("data")?.let { d ->
        d.str("call_id")?.let { callId ->
            TimelineEntryVm.ToolResult(callId, d.str("turn_id"), d.obj("content")?.toContentValue() ?: return null)
        }
    }
    "compaction" -> obj("data")?.let { d ->
        d.obj("summary")?.toContentValue()?.let { TimelineEntryVm.Compaction(it) }
    }
    else -> null
}

/** timeline 条目数组([SessionState.timeline])统一投影;畸形条目跳过。 */
fun parseTimelineEntries(entries: List<JsonElement>): List<TimelineEntryVm> =
    entries.mapNotNull { (it as? JsonObject)?.toTimelineEntry() }

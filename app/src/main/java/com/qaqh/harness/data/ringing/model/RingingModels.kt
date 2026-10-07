package com.qaqh.harness.data.ringing.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Ringing v2 协议常量（对应 qaqh-ringing protocol.rs / v2/mod.rs）。 */
object RingingProtocol {
    const val SCHEMA = "qaqh.Ringing"
    const val VERSION = 2
    const val BASE_PATH = "/ringing/v2"
    const val CHANNEL_CONTROL = "control"
    const val CHANNEL_CONVERSATION = "conversation"
    const val CHANNEL_TOOL = "tool"
}

// ---------- clients/open ----------

@Serializable
data class OpenRequest(
    val schema: String = RingingProtocol.SCHEMA,
    val version: Int = RingingProtocol.VERSION,
    @SerialName("client_instance_id") val clientInstanceId: String,
)

@Serializable
data class OpenResponse(
    val schema: String,
    val version: Int,
    val accepted: Boolean,
    @SerialName("client_session_id") val clientSessionId: String,
    @SerialName("server_epoch") val serverEpoch: String,
    @SerialName("lease_ttl_ms") val leaseTtlMs: Long,
    @SerialName("renew_interval_ms") val renewIntervalMs: Long,
    val capabilities: Capabilities? = null,
) {
    init {
        require(accepted) { "server refused open" }
        require(clientSessionId.isNotEmpty() && serverEpoch.isNotEmpty()) { "malformed open response" }
        require(leaseTtlMs > 0) { "malformed lease ttl" }
    }
}

@Serializable
data class Capabilities(
    val subscribe: Boolean = false,
    val interact: Boolean = false,
    val drive: Boolean = false,
    val timeline: Boolean = false,
    val service: Boolean = false,
    val content: Boolean = false,
    @SerialName("single_stream") val singleStream: Boolean = false,
    /** M0 设备鉴权：daemon 是否支持 /pairing/tokens + /pair 扫码配对。 */
    val pairing: Boolean = false,
)

// ---------- leases/renew ----------

@Serializable
data class RenewResponse(
    val ok: Boolean,
    @SerialName("lease_ttl_ms") val leaseTtlMs: Long,
    @SerialName("renew_interval_ms") val renewIntervalMs: Long,
)

// ---------- bootstrap ----------

@Serializable
data class Bootstrap(
    val schema: String,
    val version: Int,
    @SerialName("server_epoch") val serverEpoch: String,
    @SerialName("session_id") val sessionId: String,
    @SerialName("snapshot_cursor") val snapshotCursor: String,
    val control: ChannelSnapshot? = null,
    val conversation: ChannelSnapshot? = null,
    val tool: ChannelSnapshot? = null,
) {
    /** 从 control 通道状态中提取的当前活动（v2 词表：idle/running/interrupted）。 */
    val activity: String?
        get() = control?.state?.get("activity")?.jsonPrimitive?.content
}

@Serializable
data class ChannelSnapshot(
    val channel: String,
    @SerialName("state_revision") val stateRevision: Long,
    @SerialName("snapshot_version") val snapshotVersion: Int,
    val state: JsonObject,
)

// ---------- 命令信封（对应 qaqh-ringing v2/types.rs RingingV2CommandEnvelope） ----------

@Serializable
data class CommandEnvelope(
    val schema: String = RingingProtocol.SCHEMA,
    val version: Int = RingingProtocol.VERSION,
    val channel: String,
    @SerialName("command_id") val commandId: String,
    @SerialName("client_instance_id") val clientInstanceId: String,
    @SerialName("client_session_id") val clientSessionId: String,
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("expected_revision") val expectedRevision: Long? = null,
    @SerialName("driver_epoch") val driverEpoch: Long? = null,
    val command: JsonObject,
) {
    companion object {
        /** 构造 ToolPermissionRespond 命令体（对应 qaqh-domain command.rs ToolCommand::ToolPermissionRespond）。 */
        fun toolPermissionRespond(
            toolCallId: String,
            approved: Boolean,
            trustFolder: Boolean = false,
        ): JsonObject = buildJsonObject {
            put("channel", RingingProtocol.CHANNEL_TOOL)
            put("type", "tool_permission_respond")
            put("tool_call_id", toolCallId)
            put("approved", approved)
            put("trust_folder", trustFolder)
        }

        /** 构造 PlanReviewRespond 命令体（control 通道）。 */
        fun planReviewRespond(
            interactionId: String,
            approved: Boolean,
        ): JsonObject = buildJsonObject {
            put("channel", RingingProtocol.CHANNEL_CONTROL)
            put("type", "plan_review_respond")
            put("interaction_id", interactionId)
            put("approved", approved)
            put("autonomous", false)
        }

        /** 构造 session_create 命令体（control 通道；driver 门控，无人认领时放行）。 */
        fun sessionCreate(): JsonObject = buildJsonObject {
            put("channel", RingingProtocol.CHANNEL_CONTROL)
            put("type", "session_create")
            put("close_current", false)
        }

        /** 构造 conversation_send_message 命令体（conversation 通道，需 driver seat 或无人认领）。 */
        fun conversationSendMessage(text: String): JsonObject = buildJsonObject {
            put("channel", RingingProtocol.CHANNEL_CONVERSATION)
            put("type", "conversation_send_message")
            put("text", text)
        }

        /** 构造 SessionAttach 命令体（control 通道，只读订阅归属；content/owns_session 校验的前提）。 */
        fun sessionAttach(sessionId: String): JsonObject = buildJsonObject {
            put("channel", RingingProtocol.CHANNEL_CONTROL)
            put("type", "session_attach")
            put("session_id", sessionId)
        }

        /** 构造 InteractionAskDismiss 命令体（control 通道，关闭 ask_user 交互/中止挂起回合）。 */
        fun interactionAskDismiss(interactionId: String): JsonObject = buildJsonObject {
            put("channel", RingingProtocol.CHANNEL_CONTROL)
            put("type", "interaction_ask_dismiss")
            put("interaction_id", interactionId)
        }
    }
}

/** 命令提交回执（RingingV2CommandAck）。status: accepted | rejected。 */
@Serializable
data class CommandAck(
    @SerialName("command_id") val commandId: String,
    val status: String,
    val code: String? = null,
    val message: String? = null,
    @SerialName("retry_after_ms") val retryAfterMs: Long? = null,
    val existing: JsonElement? = null,
)

// ---------- SSE 事件信封（RingingV2EventEnvelope） ----------

@Serializable
data class EventEnvelope(
    val schema: String,
    val version: Int,
    @SerialName("server_epoch") val serverEpoch: String,
    @SerialName("session_id") val sessionId: String,
    @SerialName("event_id") val eventId: String,
    @SerialName("stream_key") val streamKey: JsonElement? = null,
    val delivery: String,
    val cursor: String? = null,
    @SerialName("log_id") val logId: String? = null,
    @SerialName("fact_seq") val factSeq: Long? = null,
    @SerialName("projection_index") val projectionIndex: Int? = null,
    val revision: Long? = null,
    @SerialName("causation_id") val causationId: String? = null,
    @SerialName("correlation_id") val correlationId: String? = null,
    @SerialName("ts_ms") val tsMs: Long? = null,
    val payload: JsonElement,
) {
    val isReliable: Boolean get() = delivery == "reliable"
}

/** SSE 重置事件（event: ringing.reset_required 的 data）。 */
@Serializable
data class ResetRequired(
    val schema: String,
    val version: Int,
    @SerialName("server_epoch") val serverEpoch: String,
    @SerialName("session_id") val sessionId: String,
    @SerialName("log_id") val logId: String? = null,
    @SerialName("snapshot_cursor") val snapshotCursor: String? = null,
    val reason: String,
)

// ---------- ProjectionPayload（kind + content="data" 配对标记）便捷投影 ----------

/** InteractionKind：ask | plan | permission（注意 wire 上是 "plan" 而非 "plan_review"）。 */
object InteractionKinds {
    const val ASK = "ask"
    const val PLAN = "plan"
    const val PERMISSION = "permission"
}

@Serializable
data class InteractionRequested(
    val revision: Long,
    @SerialName("interaction_id") val interactionId: String,
    @SerialName("call_id") val callId: String? = null,
    val kind: String,
    val request: JsonElement? = null,
    @SerialName("expires_at_ms") val expiresAtMs: Long? = null,
)

@Serializable
data class InteractionResolved(
    val revision: Long,
    @SerialName("interaction_id") val interactionId: String,
    val decision: JsonElement? = null,
    val verdict: String? = null,
    @SerialName("resolved_by") val resolvedBy: String? = null,
    @SerialName("resolution_seq") val resolutionSeq: Long? = null,
)

@Serializable
data class InteractionExpired(
    val revision: Long,
    @SerialName("interaction_id") val interactionId: String,
    val reason: String? = null,
)

@Serializable
data class ActivityDelta(
    val revision: Long,
    @SerialName("turn_id") val turnId: String? = null,
    @SerialName("call_id") val callId: String? = null,
    val state: String,
)

/**
 * payload 形如 `{"kind":"control_delta","data":{...}}`（serde tag="kind" content="data"）。
 * 这里手工解包为 (kind, data) 对。
 */
val EventEnvelope.payloadKind: String?
    get() = (payload as? JsonObject)?.get("kind")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }

val EventEnvelope.payloadData: JsonObject?
    get() = (payload as? JsonObject)?.get("data")?.let { (it as? JsonObject)?.jsonObject }

inline fun <reified T> EventEnvelope.decodePayloadData(json: kotlinx.serialization.json.Json): T? =
    payloadData?.let { runCatching { json.decodeFromJsonElement(serializer<T>(), it) }.getOrNull() }

// ---------- service RPC：session.list ----------

/**
 * `POST /service/session.list` 的条目（对齐 qaqh-types SessionListEntry wire 形状）。
 *
 * 兼容两代 daemon：backend-fix 之后 `running: bool` 被 8 态词表 `status` 取代
 * （not_running/idle/working/waiting_permission/waiting_ask/waiting_plan/canceled/error），
 * 且 `last_summary` 删除、新增 `title/archived/ephemeral`。旧 daemon 只发 `running`。
 * 判定与展示一律走 [runStatus]，不要直接读 [running]/[status]。
 */
@Serializable
data class SessionListEntry(
    @SerialName("session_id") val sessionId: String,
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("updated_at") val updatedAt: Long = 0,
    val model: String? = null,
    @SerialName("message_count") val messageCount: Int = 0,
    @SerialName("turn_count") val turnCount: Int = 0,
    /** 列表标题（新 daemon）；title→cwd 尾段→session_id 的 G2 契约在服务端已折好。 */
    val title: String? = null,
    /** 旧 daemon 的摘要字段，新 daemon 已删除。 */
    @SerialName("last_summary") val lastSummary: String? = null,
    /** 旧 daemon 的运行中标记，新 daemon 已删除（serde 缺键恒 false）。 */
    val running: Boolean = false,
    /** 新 daemon 的 8 态运行状态词表；旧 daemon 缺此键。 */
    val status: String? = null,
    val archived: Boolean = false,
    val ephemeral: Boolean = false,
    @SerialName("workspace_id") val workspaceId: String? = null,
) {
    /** 归一后的运行状态：优先新词表，旧 daemon 用 running 推导（working 即运行中）。 */
    val runStatus: String
        get() = status ?: if (running) "working" else "not_running"

    /** 是否正在执行回合（Home 卡呼吸点/自动发现选流的依据）。 */
    val isWorking: Boolean get() = runStatus == "working"

    /** 卡片主标题：title → 旧摘要 → 兜底。 */
    val displayTitle: String?
        get() = title?.takeIf { it.isNotBlank() } ?: lastSummary?.takeIf { it.isNotBlank() }
}

private val approvalJson = Json { ignoreUnknownKeys = true }

// ---------- approvals ----------

/**
 * GET /ringing/v2/sessions/{session_id}/approvals 的返回体。
 *
 * 双形状：admin 身份得 `{pending_permission, pending_interaction}`（canonical id 透传）；
 * device 身份得 `{challenges:[...]}`（opaque challenge_id，canonical id 不出 daemon）。
 * 消费端先看 [challenges]（device 路径，必须走 /approvals/respond 应答），
 * 为空再落到 pending_*（admin 路径，走命令通道）。
 */
@Serializable
data class Approvals(
    @SerialName("pending_permission") val pendingPermission: PendingPermission? = null,
    @SerialName("pending_interaction") val pendingInteraction: PendingInteraction? = null,
    val challenges: List<ApprovalChallenge> = emptyList(),
) {
    @Serializable
    data class PendingPermission(
        @SerialName("tool_call_id") val toolCallId: String,
        @SerialName("tool_name") val toolName: String? = null,
        @SerialName("action_summary") val actionSummary: String? = null,
        val reason: String? = null,
        val paths: List<String> = emptyList(),
        val category: String? = null,
        val level: Int? = null,
        val risk: String? = null,
        val consequence: String? = null,
    )

    @Serializable
    data class PendingInteraction(
        val id: String,
        val kind: String,
        val details: JsonElement? = null,
    )
}

/** device 身份的待审批条目（challenge.rs ApprovalChallenge::view）。 */
@Serializable
data class ApprovalChallenge(
    @SerialName("challenge_id") val challengeId: String,
    /** tool_permission | ask | plan（注意 tool 权限的 wire 值不是 "permission"）。 */
    val kind: String,
    /** 距过期的秒数（TTL 5min，一次性消费）。 */
    @SerialName("expires_in") val expiresInSeconds: Long? = null,
    val details: JsonElement? = null,
) {
    /** permission 白名单投影；ask/plan 的 details 是交互正文原样（questions/plan 文本），不走这里。 */
    val permissionDetails: ApprovalChallengeDetails?
        get() = (details as? JsonObject)?.let {
            runCatching {
                approvalJson.decodeFromString(ApprovalChallengeDetails.serializer(), it.toString())
            }.getOrNull()
        }

    companion object {
        const val KIND_TOOL_PERMISSION = "tool_permission"
        const val KIND_ASK = "ask"
        const val KIND_PLAN = "plan"
    }
}

/** challenge details 白名单字段（daemon 侧只透出这些，其余一律不给）。 */
@Serializable
data class ApprovalChallengeDetails(
    @SerialName("tool_name") val toolName: String? = null,
    @SerialName("action_summary") val actionSummary: String? = null,
    val reason: String? = null,
    val paths: List<String> = emptyList(),
    val category: String? = null,
    val level: Int? = null,
    val risk: String? = null,
    val consequence: String? = null,
)

/**
 * `POST /approvals/respond` 的决策枚举（challenge.rs command_for 的固定集合）：
 * tool_permission → approve/reject/trust（device 提交 trust 被服务端强制降级为单次放行，
 * UI 不应提供"永久信任"）；ask → submit（payload.answers）/dismiss；
 * plan → approve/reject（payload.message/autonomous）。
 */
object ApprovalDecisions {
    const val APPROVE = "approve"
    const val REJECT = "reject"
    const val TRUST = "trust"
    const val SUBMIT = "submit"
    const val DISMISS = "dismiss"
}

package com.qaqh.harness.data.ringing.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * 旧 RingingModels.kt 拆解后的存量兼容模型:这些类型被 UI 分组/审批/会话列表
 * 直接消费,形状保持不变——只换数据来源(RingingApi REST → Rust core JSON)。
 * wire 知识(信封/通道/路由)已随旧协议层删除,不再重建。
 */

// ---------- service RPC:session.list / workspace.list ----------

/**
 * `POST /service/session.list` 的条目(qaqh-types SessionListEntry wire 形状)。
 *
 * 兼容两代 daemon:backend-fix 之后 `running: bool` 被 8 态词表 `status` 取代
 * (not_running/idle/working/waiting_permission/waiting_ask/waiting_plan/canceled/error),
 * 且 `last_summary` 删除、新增 `title/archived/ephemeral`。旧 daemon 只发 `running`。
 * 判定与展示一律走 [runStatus],不要直接读 [running]/[status]。
 */
@Serializable
data class SessionListEntry(
    @SerialName("session_id") val sessionId: String,
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("updated_at") val updatedAt: Long = 0,
    val model: String? = null,
    @SerialName("message_count") val messageCount: Int = 0,
    @SerialName("turn_count") val turnCount: Int = 0,
    /** 列表标题(新 daemon);title→cwd 尾段→session_id 的 G2 契约在服务端已折好。 */
    val title: String? = null,
    /** 旧 daemon 的摘要字段,新 daemon 已删除。 */
    @SerialName("last_summary") val lastSummary: String? = null,
    /** 旧 daemon 的运行中标记,新 daemon 已删除(serde 缺键恒 false)。 */
    val running: Boolean = false,
    /** 新 daemon 的 8 态运行状态词表;旧 daemon 缺此键。 */
    val status: String? = null,
    val archived: Boolean = false,
    val ephemeral: Boolean = false,
    @SerialName("workspace_id") val workspaceId: String? = null,
) {
    /** 归一后的运行状态:优先新词表,旧 daemon 用 running 推导(working 即运行中)。 */
    val runStatus: String
        get() = status ?: if (running) "working" else "not_running"

    /** 是否正在执行回合(Home 卡呼吸点/自动发现选流的依据)。 */
    val isWorking: Boolean get() = runStatus == "working"

    /** 卡片主标题:title → 旧摘要 → 兜底。 */
    val displayTitle: String?
        get() = title?.takeIf { it.isNotBlank() } ?: lastSummary?.takeIf { it.isNotBlank() }
}

/** `POST /service/workspace.list` 条目;Home 分组的 section 来源。 */
@Serializable
data class WorkspaceEntry(
    val id: String,
    val path: String,
    val title: String,
    val order: Int = 0,
    @SerialName("session_ids") val sessionIds: List<String> = emptyList(),
    @SerialName("missing_dir") val missingDir: Boolean = false,
)

// ---------- 命令回执 ----------

/** 命令提交回执(RingingV2CommandAck)。status: accepted | rejected。 */
@Serializable
data class CommandAck(
    @SerialName("command_id") val commandId: String,
    val status: String,
    val code: String? = null,
    val message: String? = null,
    @SerialName("retry_after_ms") val retryAfterMs: Long? = null,
    val existing: JsonElement? = null,
)

// ---------- 交互词表(审批投影) ----------

/** InteractionKind:ask | plan | permission(wire 上是 "plan" 而非 "plan_review")。 */
object InteractionKinds {
    const val ASK = "ask"
    const val PLAN = "plan"
    const val PERMISSION = "permission"
}

/**
 * 会话流 interaction_requested 载荷的展示投影。数据源现在是契约
 * InteractionView(data/core/CoreModels),这里保留旧形状供 ConnectionService/
 * SessionGrouping 零改动消费;request 原样携带(ContentValue JSON)。
 */
data class InteractionRequested(
    val interactionId: String,
    val callId: String?,
    val kind: String,
    val request: JsonElement? = null,
    val expiresAtMs: Long? = null,
)

data class InteractionResolved(
    val interactionId: String,
    val decision: JsonElement? = null,
)

data class InteractionExpired(
    val interactionId: String,
    val reason: String? = null,
)

// ---------- device 审批(challenge 流) ----------

private val approvalJson = Json { ignoreUnknownKeys = true }

/**
 * device 身份 `GET /ringing/v2/sessions/{id}/approvals` 的条目
 * (RingingV2DeviceChallenge):canonical id 不出 daemon,challenge_id 是一次性
 * opaque token,应答必须走 POST /approvals/respond。
 */
@Serializable
data class ApprovalChallenge(
    @SerialName("challenge_id") val challengeId: String,
    /** tool_permission | ask | plan(tool 权限的 wire 值不是 "permission")。 */
    val kind: String,
    /** 距过期的秒数(TTL 5min,一次性消费)。 */
    @SerialName("expires_in") val expiresInSeconds: Long? = null,
    val details: JsonElement? = null,
) {
    /** permission 白名单投影;ask/plan 的 details 是交互正文原样,不走这里。 */
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

/** challenge details 白名单字段(daemon 侧只透出这些,其余一律不给)。 */
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

/** challenges 列表响应体(RingingV2DeviceChallenges)。 */
@Serializable
data class DeviceChallengesResponse(val challenges: List<ApprovalChallenge> = emptyList())

/**
 * `POST /approvals/respond` 的决策枚举(daemon command_for 固定集合):
 * tool_permission → approve/reject/trust(device 提交 trust 被服务端强制降级为
 * 单次放行);ask → submit(payload.answers)/dismiss;plan → approve/reject。
 */
object ApprovalDecisions {
    const val APPROVE = "approve"
    const val REJECT = "reject"
    const val TRUST = "trust"
    const val SUBMIT = "submit"
    const val DISMISS = "dismiss"
}

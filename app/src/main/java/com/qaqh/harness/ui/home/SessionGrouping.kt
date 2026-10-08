package com.qaqh.harness.ui.home

import com.qaqh.harness.connection.PendingApproval
import com.qaqh.harness.data.ringing.model.InteractionKinds
import com.qaqh.harness.data.ringing.model.SessionListEntry
import com.qaqh.harness.data.ringing.model.WorkspaceEntry
import java.time.Instant
import java.time.ZoneId

/**
 * 首页/聊天头共用的会话活动视图。两代事实源合成（后端 G2 v2 契约，
 * feat/session-run-status-g2：session.list `running:bool` → `status` 8 态词表）：
 * - `status`（fact 投影：挂起 interaction > control activity > 最近回合终态）
 *   是跨会话的权威来源，新鲜度 = 15s 轮询；canceled/error 是驻留终态，
 *   not_running 显式存在且**不得**渲染成空闲；
 * - 当前挂接会话另有 SSE live activity（idle|running|interrupted 三态）与本地
 *   pending 审批（control 投影挂起交互不改 activity）作实时覆盖。
 * 旧 daemon 无 status 键 → 退 entry.running + live 本地推导。
 */
enum class ActivityView(val label: String) {
    WORKING("工作中"),
    IDLE("空闲"),
    INTERRUPTED("已中断"),
    CANCELED("已取消"),
    ERROR("出错"),
    NOT_RUNNING("未加载"),
    WAITING_PERMISSION("等待授权"),
    WAITING_ASK("等待回答"),
    WAITING_PLAN("等待评审"),
}

/** session.list status 8 态词表 → 视图；缺键（旧 daemon）或未知词返回 null（走本地推导退路）。 */
fun runStatusToView(status: String?): ActivityView? = when (status) {
    "not_running" -> ActivityView.NOT_RUNNING
    "idle" -> ActivityView.IDLE
    "working" -> ActivityView.WORKING
    "waiting_permission" -> ActivityView.WAITING_PERMISSION
    "waiting_ask" -> ActivityView.WAITING_ASK
    "waiting_plan" -> ActivityView.WAITING_PLAN
    "canceled" -> ActivityView.CANCELED
    "error" -> ActivityView.ERROR
    else -> null
}

private fun pendingViewOf(pending: List<PendingApproval>): ActivityView? = when {
    pending.any { it.kind == InteractionKinds.PERMISSION } -> ActivityView.WAITING_PERMISSION
    pending.any { it.kind == InteractionKinds.ASK } -> ActivityView.WAITING_ASK
    pending.any { it.kind == InteractionKinds.PLAN } -> ActivityView.WAITING_PLAN
    else -> null
}

/** "running"/"working"/"starting" 为 domain 词表的防御性归并。 */
private fun isWorkingLive(activity: String?): Boolean =
    activity == "running" || activity == "working" || activity == "starting"

/**
 * entry（可空）+ **仅当前挂接会话**的 liveActivity/pending → 活动视图；
 * 非当前会话调用方须传 null/空列表（修"全局 activity 套所有卡片"的老 bug）。
 * 合成规则：
 * - 本地 pending 覆盖 waiting_*（实时事实盖轮询滞后，permission>ask>plan）；
 * - status 词表命中为准；实时 live 只补轮询空窗：working 把 idle/not_running/
 *   canceled/error 驻留态升为工作中（新回合开始即解除驻留），interrupted 覆盖
 *   除 not_running 外的非等待态（未加载 ≠ 中断）；
 * - 无 status（旧 daemon / 未知词）→ entry.running + live 本地推导。
 */
fun activityViewOf(
    entry: SessionListEntry?,
    liveActivity: String?,
    pending: List<PendingApproval>,
): ActivityView {
    pendingViewOf(pending)?.let { return it }
    val statusView = runStatusToView(entry?.status)
    if (statusView != null) {
        val waiting = statusView == ActivityView.WAITING_PERMISSION ||
            statusView == ActivityView.WAITING_ASK ||
            statusView == ActivityView.WAITING_PLAN
        if (!waiting && isWorkingLive(liveActivity)) return ActivityView.WORKING
        if (!waiting && statusView != ActivityView.NOT_RUNNING && liveActivity == "interrupted") {
            return ActivityView.INTERRUPTED
        }
        return statusView
    }
    return when {
        // 退路：status 缺键走归一值，未知词额外看原始 running（两代字段并存的过渡形状）
        entry?.isWorking == true || entry?.running == true || isWorkingLive(liveActivity) -> ActivityView.WORKING
        liveActivity == "interrupted" -> ActivityView.INTERRUPTED
        else -> ActivityView.IDLE
    }
}

/** 首页分区：一个工作区（或未分组）下的会话集合，updated_at 降序。 */
data class SessionSection(
    /** 所属工作区 id；null = 未分组（无注册表归属或注册表缺项）。 */
    val workspaceId: String?,
    val title: String,
    /** 工作区目录在 daemon 侧缺失（磁盘被移走）——卡片/头可做降级提示。 */
    val missingDir: Boolean,
    val sessions: List<SessionListEntry>,
)

/**
 * 会话 → 工作区分组（首页信息架构，竖屏长列表的可扫读性来源）。
 * - workspace_id 指向不存在的注册表项（工作区被删 / 旧 daemon 不带注册表）→ 归入未分组；
 * - 只出现有会话的工作区分区（注册表里的空工作区不制造幽灵 section）；
 * - 未分组置于最前（联调期多数会话未归属，等于保持既有"最近活动置顶"的读法）；
 * - 工作区分区按注册顺序（order），分区内按 updated_at 降序。
 */
fun groupSessionsByWorkspace(
    sessions: List<SessionListEntry>,
    workspaces: List<WorkspaceEntry>,
): List<SessionSection> {
    val byId = workspaces.associateBy { it.id }
    val grouped = sessions.groupBy { entry -> entry.workspaceId?.takeIf { byId.containsKey(it) } }
    val sections = mutableListOf<SessionSection>()
    grouped[null]?.let { list ->
        sections += SessionSection(null, "未分组", false, list.sortedByDescending { it.updatedAt })
    }
    workspaces.filter { grouped.containsKey(it.id) }
        .sortedBy { it.order }
        .forEach { ws ->
            val title = ws.title.ifBlank { ws.path.substringAfterLast('/').ifBlank { ws.id } }
            sections += SessionSection(ws.id, title, ws.missingDir, grouped.getValue(ws.id).sortedByDescending { it.updatedAt })
        }
    return sections
}

/** updated_at（epoch 秒）→ 相对时间标签（卡片 meta 行）；nowMs 注入以便测试。 */
fun relativeTimeLabel(updatedAtSec: Long, nowMs: Long): String {
    val deltaSec = ((nowMs - updatedAtSec * 1000) / 1000).coerceAtLeast(0)
    return when {
        deltaSec < 60 -> "刚刚"
        deltaSec < 3600 -> "${deltaSec / 60} 分钟前"
        deltaSec < 86_400 -> "${deltaSec / 3600} 小时前"
        deltaSec < 7 * 86_400 -> "${deltaSec / 86_400} 天前"
        else -> {
            val date = Instant.ofEpochSecond(updatedAtSec).atZone(ZoneId.systemDefault()).toLocalDate()
            "%d/%02d/%02d".format(date.year, date.monthValue, date.dayOfMonth)
        }
    }
}

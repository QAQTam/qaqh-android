package com.qaqh.harness.ui.home

import com.qaqh.harness.connection.PendingApproval
import com.qaqh.harness.data.ringing.model.InteractionKinds
import com.qaqh.harness.data.ringing.model.SessionListEntry
import com.qaqh.harness.data.ringing.model.WorkspaceEntry
import java.time.Instant
import java.time.ZoneId

/**
 * 首页/聊天头共用的会话活动视图。后端 control 投影的 activity 只有
 * idle|running|interrupted 三态（session_fact_v2 types.rs），交互挂起不改 activity、
 * 无 error 变体——`waiting_*` 由前端本地推导（对齐桌面 projection.ts:398：
 * pending interactions > 0 时覆盖为等待态）。error 属后端 gap（投影无 Failed 臂），
 * 本视图暂不含。
 */
enum class ActivityView(val label: String) {
    WORKING("工作中"),
    IDLE("空闲"),
    INTERRUPTED("已中断"),
    WAITING_PERMISSION("等待授权"),
    WAITING_ASK("等待回答"),
    WAITING_PLAN("等待评审"),
}

/** activity（投影三态；"working"/"starting" 为 domain 词表的防御性归并）+ 待审批 → 活动视图。 */
fun activityViewOf(activity: String?, pending: List<PendingApproval>): ActivityView = when {
    pending.any { it.kind == InteractionKinds.PERMISSION } -> ActivityView.WAITING_PERMISSION
    pending.any { it.kind == InteractionKinds.ASK } -> ActivityView.WAITING_ASK
    pending.any { it.kind == InteractionKinds.PLAN } -> ActivityView.WAITING_PLAN
    activity == "running" || activity == "working" || activity == "starting" -> ActivityView.WORKING
    activity == "interrupted" -> ActivityView.INTERRUPTED
    else -> ActivityView.IDLE
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

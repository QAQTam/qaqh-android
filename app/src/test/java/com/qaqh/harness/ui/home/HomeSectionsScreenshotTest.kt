package com.qaqh.harness.ui.home

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.qaqh.harness.connection.PendingApproval
import com.qaqh.harness.data.ringing.RingingConnection
import com.qaqh.harness.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 首页工作区分组视觉基线（Roborazzi，JVM/Robolectric 渲染，无需真机）。
 * 基线：`./gradlew recordRoborazziDebug`；回归：`./gradlew verifyRoborazziDebug`。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeSectionsScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun homeWorkspaceSectionsBaseline() {
        composeRule.setContent {
            AppTheme {
                HomeList(
                    state = RingingConnection.State.Connected("epoch-1", "cs-1"),
                    activity = "running",
                    pending = emptyList(),
                    currentSessionId = "s2",
                    sections = sampleSections(),
                    onOpenSession = {},
                )
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/screenshots/home_sections.png")
    }

    @Test
    fun homeWaitingPermissionBaseline() {
        composeRule.setContent {
            AppTheme {
                HomeList(
                    state = RingingConnection.State.Connected("epoch-1", "cs-1"),
                    activity = "running",
                    pending = listOf(
                        // permission 挂起 → 当前会话卡（s2）显示"等待授权"；其余卡不受全局数据污染
                        PendingApproval(
                            interactionId = "int-1",
                            kind = "permission",
                            callId = null,
                            summary = "同意执行 rm -rf /tmp/x",
                            expiresAtMs = null,
                        ),
                    ),
                    currentSessionId = "s2",
                    sections = sampleSections(),
                    onOpenSession = {},
                )
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/screenshots/home_waiting.png")
    }

    /** 新 daemon（G2 v2）：8 态 status 词表全量上屏，无 live 覆盖（currentSessionId=null）。 */
    @Test
    @Config(sdk = [35], qualifiers = "w360dp-h1200dp")
    fun homeRunStatusVocabularyBaseline() {
        val nowSec = System.currentTimeMillis() / 1000
        fun entry(id: String, status: String, title: String) =
            com.qaqh.harness.data.ringing.model.SessionListEntry(
                sessionId = id,
                updatedAt = nowSec,
                model = "glm-4.7",
                title = title,
                turnCount = 3,
                status = status,
            )
        val sections = listOf(
            SessionSection(
                workspaceId = null,
                title = "未分组",
                missingDir = false,
                sessions = listOf(
                    entry("r8", "error", "跑批处理脚本时模型输出中断"),
                    entry("r7", "canceled", "把这份报告翻译成英文"),
                    entry("r6", "waiting_plan", "评审一下这个重构方案"),
                    entry("r5", "waiting_ask", "帮我对比两个候选库"),
                    entry("r4", "waiting_permission", "删除旧归档目录"),
                    entry("r3", "working", "接入 session list 状态词表"),
                    entry("r2", "idle", "整理 worklog 笔记"),
                    entry("r1", "not_running", "归档的旧会话"),
                ),
            ),
        )
        composeRule.setContent {
            AppTheme {
                HomeList(
                    state = RingingConnection.State.Connected("epoch-1", "cs-1"),
                    activity = null,
                    pending = emptyList(),
                    currentSessionId = null,
                    sections = sections,
                    onOpenSession = {},
                )
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/screenshots/home_runstatus.png")
    }
}

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
                        // permission 挂起 → 卡片应显示"等待授权"（本地推导，非后端下发）
                        PendingApproval(
                            interactionId = "int-1",
                            kind = "permission",
                            callId = null,
                            summary = "同意执行 rm -rf /tmp/x",
                            expiresAtMs = null,
                        ),
                    ),
                    sections = sampleSections(),
                    onOpenSession = {},
                )
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/screenshots/home_waiting.png")
    }
}

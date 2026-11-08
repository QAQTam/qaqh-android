package com.qaqh.harness.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * M3 形状 scale(5 档语义圆角,全 app 唯一圆角出处;显式 RoundedCornerShape 只允许
 * 玻璃结构/状态点等非语义场景)。各档消费方:M3 组件默认值 + 各屏 MaterialTheme.shapes 引用。
 */
private fun appShapes(): Shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),   // 代码块、Snackbar(原 baseline 4)
    small = RoundedCornerShape(12.dp),       // 按钮/chip/消息气泡(原 8)
    medium = RoundedCornerShape(16.dp),      // TextField/默认 Card(原 12)
    large = RoundedCornerShape(20.dp),       // FAB(原 16)
    extraLarge = RoundedCornerShape(28.dp),  // 大卡/Sheet/对话框(baseline 同值)
)

/**
 * 应用主题:Material You 动态取色(API 31+,与系统壁纸联动)+ M3 Expressive
 * 动效(MotionScheme.expressive,组件内部动画走高弹 spring)+ 形状 scale。
 * 字体 = 系统默认(2026-11 起移除内嵌 HarmonyOS Sans/Cascadia,见 docs/fonts.md;
 * 系统字体真·可变字重,W500/W600 名副其实;等宽场景直接用 FontFamily.Monospace)。
 * material3 钉 1.5.0-rc01(见 libs.versions.toml):BOM 2026.09.00 的 1.4.0 把
 * expressive API 整体 Kotlin-internal 化,1.5.0 线才转正。
 * 玻璃材质的 tint 都从 colorScheme.surface 取,跟随主题。
 */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialExpressiveTheme(
        colorScheme = scheme,
        shapes = appShapes(),
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}

package com.qaqh.harness.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.qaqh.harness.R

/**
 * 四端统一的字体（Android / HarmonyOS / 桌面 / WinUI 同源文件，见 docs/fonts.md）：
 * - 正文/标题 = HarmonyOS Sans SC（Regular/Bold 两档静态字重，桌面仓同一套裁剪文件）；
 * - 等宽 = Cascadia Code（可变字体 wght 轴，WinUI 仓同款）。
 * M3 的 W500/W600 分别落到 Regular/Bold（官方 Medium 未随桌面仓分发，跟进时补）。
 */
val AppSans = FontFamily(
    Font(R.font.hmos_sans_sc_regular, FontWeight.W400),
    Font(R.font.hmos_sans_sc_regular, FontWeight.W500),
    Font(R.font.hmos_sans_sc_bold, FontWeight.W600),
    Font(R.font.hmos_sans_sc_bold, FontWeight.W700),
)

/** Cascadia Code 可变字体：按字重轴取实例（minSdk 31，FontVariation 全可用）。 */
private fun cascadia(weight: FontWeight): Font = Font(
    resId = R.font.cascadia_code,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

val AppMono = FontFamily(
    cascadia(FontWeight.W400),
    cascadia(FontWeight.W700),
)

/** M3 全部字槽覆盖 fontFamily：不逐槽设置的话 systemRoboto 会从默认样式漏进来。 */
private fun appTypography(): Typography {
    val base = Typography()
    return base.copy(
        displayLarge = base.displayLarge.copy(fontFamily = AppSans),
        displayMedium = base.displayMedium.copy(fontFamily = AppSans),
        displaySmall = base.displaySmall.copy(fontFamily = AppSans),
        headlineLarge = base.headlineLarge.copy(fontFamily = AppSans),
        headlineMedium = base.headlineMedium.copy(fontFamily = AppSans),
        headlineSmall = base.headlineSmall.copy(fontFamily = AppSans),
        titleLarge = base.titleLarge.copy(fontFamily = AppSans),
        titleMedium = base.titleMedium.copy(fontFamily = AppSans),
        titleSmall = base.titleSmall.copy(fontFamily = AppSans),
        bodyLarge = base.bodyLarge.copy(fontFamily = AppSans),
        bodyMedium = base.bodyMedium.copy(fontFamily = AppSans),
        bodySmall = base.bodySmall.copy(fontFamily = AppSans),
        labelLarge = base.labelLarge.copy(fontFamily = AppSans),
        labelMedium = base.labelMedium.copy(fontFamily = AppSans),
        labelSmall = base.labelSmall.copy(fontFamily = AppSans),
    )
}

/**
 * 应用主题：Material You 动态取色（API 31+，与系统壁纸联动，M3 Expressive 的地基），
 * 不可用时回退基线紫色方案。玻璃材质的 tint 都从 colorScheme.surface 取，跟随主题。
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
    MaterialTheme(colorScheme = scheme, typography = appTypography(), content = content)
}

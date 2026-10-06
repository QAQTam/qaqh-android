@file:OptIn(dev.chrisbanes.haze.ExperimentalHazeApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.qaqh.harness.ui.nav

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.glass.GlassStyle
import dev.chrisbanes.haze.glass.hazeGlass
import dev.chrisbanes.haze.glass.material3.Material3

// ---- 玻璃导航栏几何常量 ----
private val BarHeight = 64.dp
private val BarHorizontalMargin = 28.dp
private val LensCorner = 26.dp
private val ContentBottomClearance = 104.dp // 列表内容为悬浮条预留的底部净空

/** 悬浮条要遮挡的底部 inset + 自身高度 + 间距。 */
val GlassNavBarContentPadding = ContentBottomClearance

/** 底部导航项。 */
data class GlassNavItem(
    val icon: ImageVector,
    val selectedIcon: ImageVector = icon,
    val label: String,
    val badge: Int = 0,
)

/**
 * iOS 27 风格悬浮液态玻璃导航条（与鸿蒙端对齐）：
 * 条身 = 低 tint 玻璃（列表内容透过来，边缘折射）；选中项 = 独立玻璃透镜，
 * spring 滑动切换（真机折射光效集中在透镜上，即参考图的形态）。
 */
@Composable
fun GlassNavBar(
    items: List<GlassNavItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
    hazeState: HazeState,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
        val surface = MaterialTheme.colorScheme.surface
        BoxWithConstraints(
            Modifier
                .padding(horizontal = BarHorizontalMargin)
                .fillMaxWidth()
                .height(BarHeight)
                .offset(y = (-8).dp)
                .zIndex(1f)
                .shadow(18.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.25f))
                .clip(CircleShape)
                // 条身：低不透明度（backgroundColor 只留薄纱），折射与模糊透出内容
                .hazeGlass(
                    input = HazeInput.Sources(hazeState),
                    style = GlassStyle.Material3(containerColor = surface.copy(alpha = 0.28f)) {
                        shape(CircleShape)
                        tint(Color.White.copy(alpha = 0.08f))
                    },
                )
                .border(
                    width = 1.dp,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.55f),
                            Color.White.copy(alpha = 0.12f),
                        ),
                    ),
                    shape = CircleShape,
                ),
        ) {
            val itemWidth = maxWidth / items.size
            // 选中透镜：滑动的独立玻璃药丸（比条身更"厚"——更高 tint + 阴影浮起）
            val lensX by animateDpAsState(
                targetValue = itemWidth * selected,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
                label = "lensSlide",
            )
            val lensShape = RoundedCornerShape(LensCorner)
            Box(
                Modifier
                    .offset(x = lensX)
                    .size(width = itemWidth, height = BarHeight)
                    .padding(horizontal = 3.dp, vertical = 4.dp)
                    .shadow(10.dp, lensShape, ambientColor = Color.Black.copy(alpha = 0.30f))
                    .clip(lensShape)
                    .hazeGlass(
                        input = HazeInput.Sources(hazeState),
                        style = GlassStyle.Material3(containerColor = surface.copy(alpha = 0.45f)) {
                            shape(lensShape)
                            tint(Color.White.copy(alpha = 0.20f))
                        },
                    )
                    .border(
                        width = 1.dp,
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.70f),
                                Color.White.copy(alpha = 0.15f),
                            ),
                        ),
                        shape = lensShape,
                    ),
            )
            Row(Modifier.fillMaxSize()) {
                items.forEachIndexed { index, item ->
                    NavItem(
                        item = item,
                        selected = index == selected,
                        modifier = Modifier.weight(1f),
                        onClick = { onSelect(index) },
                    )
                }
            }
        }
    }
}

@Composable
private fun NavItem(
    item: GlassNavItem,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint = if (selected) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(modifier.padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(1.dp),
            modifier = Modifier
                .clip(RoundedCornerShape(LensCorner))
                .clickableNoRipple(onClick)
                .padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Box {
                Icon(
                    if (selected) item.selectedIcon else item.icon,
                    contentDescription = item.label,
                    tint = tint,
                    modifier = Modifier.size(22.dp),
                )
                if (item.badge > 0) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 6.dp, y = (-2).dp)
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.error),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (item.badge > 9) "9+" else item.badge.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onError,
                        )
                    }
                }
            }
            Text(
                item.label,
                style = MaterialTheme.typography.labelSmall,
                color = tint,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    return clickable(
        interactionSource = source,
        indication = null,
        onClick = onClick,
    )
}

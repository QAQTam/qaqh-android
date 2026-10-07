@file:OptIn(dev.chrisbanes.haze.ExperimentalHazeApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.qaqh.harness.ui.nav

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.glass.GlassStyle
import dev.chrisbanes.haze.glass.hazeGlass
import dev.chrisbanes.haze.glass.material3.Material3
import kotlin.math.abs
import kotlin.math.roundToInt

// ---- 玻璃导航栏几何常量 ----
private val BarHeight = 64.dp
private val BarHorizontalMargin = 28.dp
private val LensCorner = 26.dp
private val ContentBottomClearance = 118.dp // 列表内容为悬浮条预留的底部净空
private val BarBottomLift = 22.dp // 条底离屏幕底的抬升（真机反馈：原 8dp 太低，抬高 ~50 物理px @3.5x）
/** 拖动时透镜最大拉伸比例（squash & stretch 质感）。 */
private const val MAX_STRETCH = 0.06f

/** 悬浮条要遮挡的底部 inset + 自身高度 + 间距。 */
val GlassNavBarContentPadding = ContentBottomClearance

/** 底部导航项。 */
data class GlassNavItem(
    val icon: ImageVector,
    val selectedIcon: ImageVector = icon,
    val label: String,
    val badge: Int = 0,
)

private fun springLens() = spring<Float>(
    dampingRatio = Spring.DampingRatioLowBouncy,
    stiffness = Spring.StiffnessMediumLow,
)

/**
 * iOS 27 风格悬浮液态玻璃导航条（与鸿蒙端对齐）：
 * 条身 = 低 tint 玻璃；选中项 = 独立玻璃透镜。
 * 透镜**可横向拖拽**：跟手（snapTo）+ 越过中线 haptic 触感 + 拖动拉伸变形，
 * 松手按初速度弹簧收敛到最近 tab；轻点 = 直接切 tab。
 *
 * 手势结构：整个导航条**单一手势主人**（本 Box 的 pointerInput，awaitEachGesture +
 * Initial pass）。不能把拖拽挂在透镜上——透镜在 z 序下面，NavItem 的 clickable
 * 会在 Main pass 先消费 down，透镜的 detectHorizontalDragGestures 要求未消费 down，
 * 永远等不到（真机实测拖拽零响应）。拖拽期间透镜位置以闭包局部变量为准——
 * Animatable.snapTo 是异步协程，事件比调度快且 snapTo 会取消上一个 pending
 * snapTo，直接读 Animatable.value 会丢中间帧增量（实测落点只有真实距离 ~45%）。
 */
@Composable
fun GlassNavBar(
    items: List<GlassNavItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
    hazeState: HazeState,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    // 手势 lambda 的 key 不含 selected/onSelect（否则每次选中变化都重启手势），
    // 闭包里拿到的是首组合的旧值——必须经 rememberUpdatedState 取最新
    // （否则 selected 变化后点回旧值 tab 会被 `tapped != selected` 误吞）
    val currentSelected by androidx.compose.runtime.rememberUpdatedState(selected)
    val currentOnSelect by androidx.compose.runtime.rememberUpdatedState(onSelect)
    // 透镜进度：单位 = tab 格数（0..lastIndex）的 Animatable——拖拽 snapTo 跟手、
    // 松手 animateTo 弹簧收敛（初速度 = 拖动初速度换算成格/秒）
    val lensProgress = remember { Animatable(selected.toFloat()) }
    var previewIndex by remember { mutableIntStateOf(selected) }
    var dragging by remember { mutableStateOf(false) }
    // 拖动拉伸量（squash & stretch），松手回弹到 0
    val stretch = remember { Animatable(0f) }

    // tap 切换：透镜弹簧滑过去（拖拽中不抢）
    LaunchedEffect(selected) {
        if (!dragging) {
            previewIndex = selected
            lensProgress.animateTo(selected.toFloat(), springLens())
        }
    }

    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
        val surface = MaterialTheme.colorScheme.surface
        BoxWithConstraints(
            Modifier
                .padding(horizontal = BarHorizontalMargin)
                .fillMaxWidth()
                .height(BarHeight)
                .offset(y = -BarBottomLift)
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
                )
                .pointerInput(items.size) {
                    val lastIdx = items.size - 1
                    val itemPx = size.width.toFloat() / items.size
                    awaitEachGesture {
                        // Initial pass：先于任何子节点消费拿到 down
                        val down = awaitFirstDown(pass = PointerEventPass.Initial)
                        val downX = down.position.x
                        var tracker = VelocityTracker()
                        var gestureUnits = lensProgress.value
                        var started = false
                        val slop = viewConfiguration.touchSlop
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            val dx = change.position.x - downX
                            val dy = change.position.y - down.position.y
                            if (!started && abs(dx) > slop && abs(dx) > abs(dy)) {
                                started = true
                                dragging = true
                                gestureUnits = lensProgress.value
                                tracker = VelocityTracker()
                            }
                            if (started) {
                                if (change.positionChanged()) {
                                    tracker.addPosition(change.uptimeMillis, change.position)
                                }
                                val deltaUnits = change.positionChange().x / itemPx
                                gestureUnits = (gestureUnits + deltaUnits).coerceIn(0f, lastIdx.toFloat())
                                scope.launch { lensProgress.snapTo(gestureUnits) }
                                // 越过中线：预览切换 + 轻触感
                                val idx = gestureUnits.roundToInt().coerceIn(0, lastIdx)
                                if (idx != previewIndex) {
                                    previewIndex = idx
                                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                }
                                // 拖动拉伸（squash & stretch）：与单帧位移成正比，clamp 防夸张
                                val s = (abs(change.positionChange().x) / itemPx * 2f).coerceAtMost(MAX_STRETCH)
                                scope.launch { stretch.snapTo(s) }
                                change.consume()
                            }
                            if (!change.pressed) {
                                if (started) {
                                    dragging = false
                                    val velocity = tracker.calculateVelocity()
                                    val idx = gestureUnits.roundToInt().coerceIn(0, lastIdx)
                                    previewIndex = idx
                                    view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                    currentOnSelect(idx)
                                    scope.launch { stretch.animateTo(0f, spring(stiffness = Spring.StiffnessMedium)) }
                                    scope.launch {
                                        lensProgress.animateTo(
                                            idx.toFloat(),
                                            springLens(),
                                            initialVelocity = velocity.x / itemPx,
                                        )
                                    }
                                } else {
                                    // 轻点：down 落在哪个 tab 就切哪个
                                    val tapped = (downX / itemPx).toInt().coerceIn(0, lastIdx)
                                    if (tapped != currentSelected) {
                                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                        currentOnSelect(tapped)
                                    }
                                }
                                break
                            }
                        }
                    }
                },
        ) {
            val itemWidth = maxWidth / items.size

            val lensShape = RoundedCornerShape(LensCorner)
            // 拖动中高光描边提亮（玻璃被"捏紧"的反光暗示）
            val rimAlpha by animateFloatAsState(if (dragging) 0.85f else 0.55f, label = "rimAlpha")

            // 选中透镜：滑动的独立玻璃药丸
            Box(
                Modifier
                    .offset(x = itemWidth * lensProgress.value)
                    .size(width = itemWidth, height = BarHeight)
                    .padding(horizontal = 3.dp, vertical = 4.dp)
                    .graphicsLayer {
                        scaleX = 1f + stretch.value
                        scaleY = 1f - stretch.value * 0.4f
                    }
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
                                Color.White.copy(alpha = rimAlpha),
                                Color.White.copy(alpha = rimAlpha * 0.25f),
                            ),
                        ),
                        shape = lensShape,
                    ),
            )
            Row(Modifier.fillMaxSize()) {
                items.forEachIndexed { index, item ->
                    NavItem(
                        item = item,
                        highlighted = index == previewIndex,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun NavItem(
    item: GlassNavItem,
    highlighted: Boolean,
    modifier: Modifier = Modifier,
) {
    val tint = if (highlighted) {
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
                .padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Box {
                Icon(
                    if (highlighted) item.selectedIcon else item.icon,
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

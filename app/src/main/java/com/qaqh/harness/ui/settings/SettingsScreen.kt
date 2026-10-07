package com.qaqh.harness.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qaqh.harness.BuildConfig
import com.qaqh.harness.connection.ConnectionService
import com.qaqh.harness.data.pairing.StoredCredentials
import com.qaqh.harness.data.ringing.RingingConnection
import com.qaqh.harness.ui.nav.GlassNavBarContentPadding

/**
 * 设置屏：连接状态、配对信息（endpoint/设备/TLS 指纹）、解绑。
 * 从 Home 收拢过来——Home 只关心会话，连接细节属于设置。
 */
@Composable
fun SettingsScreen(
    credentials: StoredCredentials,
    onUnpair: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by ConnectionService.connectionState.collectAsState()
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = GlassNavBarContentPadding),
    ) {
        Text(
            "设置",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
        )
        val stateLabel = when (state) {
            is RingingConnection.State.Connected -> "已连接"
            is RingingConnection.State.Connecting -> "正在连接…"
            is RingingConnection.State.Failed -> "连接失败（自动重试中）"
            RingingConnection.State.Disconnected -> "未连接"
        }
        val stateColor = when (state) {
            is RingingConnection.State.Connected -> MaterialTheme.colorScheme.primary
            is RingingConnection.State.Connecting -> MaterialTheme.colorScheme.tertiary
            is RingingConnection.State.Failed -> MaterialTheme.colorScheme.error
            RingingConnection.State.Disconnected -> MaterialTheme.colorScheme.outline
        }
        SettingSection("连接") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(stateColor))
                Text(stateLabel, style = MaterialTheme.typography.bodyLarge)
            }
            SettingRow("服务地址", credentials.baseUrl)
        }
        SettingSection("配对") {
            SettingRow("设备名", credentials.deviceName)
            SettingRow("设备 ID", credentials.deviceId)
            credentials.tlsFingerprint?.let { fp ->
                SettingRow("TLS 指纹", fp, mono = true)
            }
            SettingRow("配对时间", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(credentials.pairedAtMs)))
        }
        SettingSection("实时活动") {
            if (android.os.Build.VERSION.SDK_INT >= 36) {
                val context = androidx.compose.ui.platform.LocalContext.current
                val promotedEnabled = remember { ConnectionService.promotedOngoingEnabled(context) }
                val (label, color) = if (promotedEnabled) {
                    "已开启" to MaterialTheme.colorScheme.primary
                } else {
                    "未开启" to MaterialTheme.colorScheme.outline
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(vertical = 8.dp),
                ) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(color))
                    Column(Modifier.weight(1f)) {
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "待办进度以流体云胶囊/锁屏卡片常驻展示（离开应用仍可见）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (!promotedEnabled) {
                    Button(
                        onClick = { ConnectionService.openPromotedSettings(context) },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    ) { Text("去系统设置开启") }
                }
            } else {
                Text(
                    "需要 Android 16+（API 36）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }
        SettingSection("关于") {
            // 彩蛋式调试入口：版本行 2 秒内连点 5 次切换内存探针
            var tapCount by remember { mutableIntStateOf(0) }
            var lastTapMs by remember { mutableLongStateOf(0L) }
            var showMemProbe by remember { mutableStateOf(false) }
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        val now = System.currentTimeMillis()
                        tapCount = if (now - lastTapMs < 2000) tapCount + 1 else 1
                        lastTapMs = now
                        if (tapCount >= 5) {
                            showMemProbe = !showMemProbe
                            tapCount = 0
                        }
                    }
                    .padding(vertical = 8.dp),
            ) {
                SettingRow("版本", BuildConfig.VERSION_NAME + if (showMemProbe) " ·探针开" else "")
            }
            if (showMemProbe) {
                MemoryProbePanel()
            }
        }
        Button(
            onClick = onUnpair,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 24.dp),
        ) { Text("解除配对") }
    }
}

@Composable
private fun SettingSection(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
        )
        androidx.compose.material3.Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) { content() }
        }
    }
}

/** 一次内存采样（单位统一 MB 渲染；PSS 来自 Debug.MemoryInfo，kB 字段）。 */
private class MemSample(
    pssTotalKb: Int,
    pssJavaKb: Int,
    pssNativeKb: Int,
    javaUsed: Long,
    javaMax: Long,
    sysAvail: Long,
    sysTotal: Long,
    val lowMemory: Boolean,
) {
    val pssTotal = pssTotalKb / 1024f
    val pssJava = pssJavaKb / 1024f
    val pssNative = pssNativeKb / 1024f
    val pssOther = (pssTotalKb - pssJavaKb - pssNativeKb).coerceAtLeast(0) / 1024f
    val javaUsedMb = javaUsed / 1048576f
    val javaMaxMb = javaMax / 1048576f
    val sysAvailMb = sysAvail / 1048576f
    val sysTotalMb = sysTotal / 1048576f
}

private fun sampleMemory(context: android.content.Context): MemSample {
    val am = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
    val dbg = android.os.Debug.MemoryInfo()
    android.os.Debug.getMemoryInfo(dbg)
    val rt = Runtime.getRuntime()
    val sys = android.app.ActivityManager.MemoryInfo()
    am.getMemoryInfo(sys)
    return MemSample(
        pssTotalKb = dbg.totalPss,
        pssJavaKb = dbg.dalvikPss,
        pssNativeKb = dbg.nativePss,
        javaUsed = rt.totalMemory() - rt.freeMemory(),
        javaMax = rt.maxMemory(),
        sysAvail = sys.availMem,
        sysTotal = sys.totalMem,
        lowMemory = sys.lowMemory,
    )
}

/** 内存探针面板：显示期间每 2s 采样一次（离开组合自动停止）。 */
@Composable
private fun MemoryProbePanel() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var sample by remember { androidx.compose.runtime.mutableStateOf<MemSample?>(null) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            sample = sampleMemory(context)
            kotlinx.coroutines.delay(2000)
        }
    }
    val s = sample ?: return
    val mono = MaterialTheme.typography.bodyMedium.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        HorizontalDivider(Modifier.padding(bottom = 8.dp))
        MemRow("应用 PSS 总量", "%.1f MB".format(s.pssTotal), mono)
        MemRow("├ Java 堆", "%.1f MB".format(s.pssJava), mono)
        MemRow("├ 原生堆", "%.1f MB".format(s.pssNative), mono)
        MemRow("└ 其他（代码/图形…）", "%.1f MB".format(s.pssOther), mono)
        MemRow("运行时堆 已用/上限", "%.0f / %.0f MB".format(s.javaUsedMb, s.javaMaxMb), mono)
        MemRow("系统 可用/总量", "%.2f / %.2f GB".format(s.sysAvailMb / 1024f, s.sysTotalMb / 1024f), mono)
        if (s.lowMemory) {
            Text(
                "⚠ 系统低内存",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun MemRow(label: String, value: String, style: androidx.compose.ui.text.TextStyle) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = style)
    }
}

@Composable
private fun SettingRow(label: String, value: String, mono: Boolean = false) {    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = if (mono) {
                MaterialTheme.typography.bodyMedium.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            } else {
                MaterialTheme.typography.bodyMedium
            },
            maxLines = 2,
            overflow = TextOverflow.MiddleEllipsis,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
    }
}

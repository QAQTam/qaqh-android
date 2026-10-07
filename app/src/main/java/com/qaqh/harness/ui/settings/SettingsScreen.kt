package com.qaqh.harness.ui.settings

import androidx.compose.foundation.background
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
import androidx.compose.runtime.remember
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
            SettingRow("版本", BuildConfig.VERSION_NAME)
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

@Composable
private fun SettingRow(label: String, value: String, mono: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
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

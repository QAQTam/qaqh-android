package com.qaqh.harness

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.ChatBubble
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.qaqh.harness.connection.ConnectionService
import com.qaqh.harness.data.pairing.CredentialStore
import com.qaqh.harness.data.pairing.PairingClient
import com.qaqh.harness.data.pairing.PairingPayload
import com.qaqh.harness.data.pairing.PairingPayloadException
import com.qaqh.harness.data.pairing.StoredCredentials
import com.qaqh.harness.ui.home.HomeScreen
import com.qaqh.harness.ui.nav.GlassNavItem
import com.qaqh.harness.ui.nav.GlassNavBar
import com.qaqh.harness.ui.pairing.PairingScreen
import com.qaqh.harness.ui.session.SessionScreen
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (com.qaqh.harness.BuildConfig.DEBUG) {
            // JankStats：流式渲染逐帧耗时采集；>32ms 的帧打日志（logcat 过滤 JankStats）
            androidx.metrics.performance.JankStats.createAndTrack(window) { frameData ->
                val ms = frameData.frameDurationUiNanos / 1_000_000.0
                if (ms >= 32) {
                    android.util.Log.w("JankStats", "jank frame %.1fms states=${frameData.states}".format(ms))
                }
            }
        }
        setContent {
            com.qaqh.harness.ui.theme.AppTheme {
                AppRoot(Modifier)
            }
        }
    }
}

@Composable
fun AppRoot(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember { CredentialStore(context) }
    var credentials by remember { mutableStateOf<StoredCredentials?>(store.load()) }
    var pairing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val deviceName = remember { "${Build.MODEL}（Android）" }
    val client = remember(deviceName) { PairingClient(deviceName) }

    // 审批通知需要运行时通知权限（API 33+）
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // 已配对则拉起前台连接服务
    LaunchedEffect(credentials) {
        if (credentials != null) ConnectionService.start(context)
    }

    // 两级导航：tab 壳 ↔ 聊天屏（聊天全屏覆盖 tab，自带返回）
    var openSessionId by remember { mutableStateOf<String?>(null) }

    // 设备被吊销（服务端全 401）：自动掉回配对屏
    LaunchedEffect(Unit) {
        ConnectionService.authRevoked.collect { revoked ->
            if (revoked) {
                credentials = null
                openSessionId = null
                ConnectionService.consumeAuthRevoked()
            }
        }
    }

    if (credentials == null) {
        PairingScreen(
            pairing = pairing,
            error = error,
            onPayload = { raw ->
                if (!pairing) {
                    pairing = true
                    error = null
                    scope.launch {
                        try {
                            val payload = PairingPayload.parse(raw)
                            val creds = client.pair(payload)
                            store.save(creds)
                            credentials = creds
                        } catch (e: PairingPayloadException) {
                            error = e.message
                        } catch (e: Exception) {
                            error = "配对失败：${e.message}"
                        } finally {
                            pairing = false
                        }
                    }
                }
            },
            onManual = { endpoint, token, tlsFp ->
                if (!pairing) {
                    pairing = true
                    error = null
                    scope.launch {
                        try {
                            val creds = client.pair(PairingPayload.Direct(endpoint, token, tlsFp))
                            store.save(creds)
                            credentials = creds
                        } catch (e: Exception) {
                            error = "配对失败：${e.message}"
                        } finally {
                            pairing = false
                        }
                    }
                }
            },
        )
    } else {
        val creds = credentials!!
        if (openSessionId != null) {
            BackHandler { openSessionId = null }
            SessionScreen(
                sessionId = openSessionId!!,
                onBack = { openSessionId = null },
            )
        } else {
            TabShell(creds, onUnpair = {
                ConnectionService.stop(context)
                store.clear()
                credentials = null
            }, onOpenSession = { openSessionId = it })
        }
    }
}

/** 底部 tab 壳：内容 + 悬浮玻璃导航条（内容从条下滚过，经 hazeSource 供模糊采样）。 */
@Composable
private fun TabShell(
    credentials: StoredCredentials,
    onUnpair: () -> Unit,
    onOpenSession: (String) -> Unit,
) {
    var tab by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val hazeState = dev.chrisbanes.haze.rememberHazeState()
    val approvals by ConnectionService.pendingApprovals.collectAsState()
    val items = remember(approvals.size) {
        listOf(
            com.qaqh.harness.ui.nav.GlassNavItem(
                icon = androidx.compose.material.icons.Icons.Outlined.ChatBubble,
                selectedIcon = androidx.compose.material.icons.Icons.Filled.ChatBubble,
                label = "会话",
            ),
            com.qaqh.harness.ui.nav.GlassNavItem(
                icon = androidx.compose.material.icons.Icons.Outlined.VerifiedUser,
                selectedIcon = androidx.compose.material.icons.Icons.Filled.VerifiedUser,
                label = "审批",
                badge = approvals.size,
            ),
            com.qaqh.harness.ui.nav.GlassNavItem(
                icon = androidx.compose.material.icons.Icons.Outlined.Build,
                selectedIcon = androidx.compose.material.icons.Icons.Filled.Build,
                label = "工具",
            ),
            com.qaqh.harness.ui.nav.GlassNavItem(
                icon = androidx.compose.material.icons.Icons.Outlined.Settings,
                selectedIcon = androidx.compose.material.icons.Icons.Filled.Settings,
                label = "设置",
            ),
        )
    }
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .hazeSource(hazeState),
        ) {
            when (tab) {
                0 -> HomeScreen(
                    credentials = credentials,
                    onOpenSession = onOpenSession,
                    modifier = Modifier.fillMaxSize(),
                )
                1 -> com.qaqh.harness.ui.approvals.ApprovalsScreen(Modifier.fillMaxSize())
                2 -> com.qaqh.harness.ui.tools.ToolsScreen(Modifier.fillMaxSize())
                else -> com.qaqh.harness.ui.settings.SettingsScreen(
                    credentials = credentials,
                    onUnpair = onUnpair,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        com.qaqh.harness.ui.nav.GlassNavBar(
            items = items,
            selected = tab,
            onSelect = { tab = it },
            hazeState = hazeState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

package com.qaqh.harness

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.activity.compose.BackHandler
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import com.qaqh.harness.ui.pairing.PairingScreen
import com.qaqh.harness.ui.session.SessionScreen
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
            MaterialTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    AppRoot(innerPadding, Modifier)
                }
            }
        }
    }
}

@Composable
fun AppRoot(innerPadding: androidx.compose.foundation.layout.PaddingValues, modifier: Modifier = Modifier) {
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

    // 两级导航：会话列表 ↔ 聊天屏
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
        if (openSessionId == null) {
            HomeScreen(
                credentials = creds,
                onUnpair = {
                    ConnectionService.stop(context)
                    store.clear()
                    credentials = null
                    openSessionId = null
                },
                onOpenSession = { openSessionId = it },
                modifier = modifier.padding(innerPadding),
            )
        } else {
            BackHandler { openSessionId = null }
            SessionScreen(
                sessionId = openSessionId!!,
                onBack = { openSessionId = null },
                modifier = Modifier.padding(top = innerPadding.calculateTopPadding()),
            )
        }
    }
}

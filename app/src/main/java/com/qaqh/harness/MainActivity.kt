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
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    AppRoot(Modifier.padding(innerPadding))
                }
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
            onManual = { endpoint, token ->
                if (!pairing) {
                    pairing = true
                    error = null
                    scope.launch {
                        try {
                            val creds = client.pair(PairingPayload.Direct(endpoint, token))
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
        HomeScreen(
            credentials = credentials!!,
            onUnpair = {
                ConnectionService.stop(context)
                store.clear()
                credentials = null
            },
            modifier = modifier,
        )
    }
}

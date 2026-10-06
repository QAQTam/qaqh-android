package com.qaqh.harness.ui.pairing

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

/**
 * 配对屏：相机扫码（ML Kit 二维码）+ 手输降级。
 * 扫到/输入的内容原样交给 [onPayload]，解析与配对执行在上层。
 */
@Composable
fun PairingScreen(
    pairing: Boolean,
    error: String?,
    onPayload: (String) -> Unit,
    onManual: (endpoint: String, token: String, tlsFingerprint: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    var showManual by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasCameraPermission = it
    }

    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("配对电脑端", style = MaterialTheme.typography.headlineSmall)
        Text(
            "在电脑端打开二维码，对准扫描框；或手动输入地址与 token。",
            style = MaterialTheme.typography.bodyMedium,
        )

        if (hasCameraPermission) {
            Box(
                modifier = Modifier.fillMaxWidth().height(300.dp),
                contentAlignment = Alignment.Center,
            ) {
                QrCameraPreview(onQrText = onPayload)
                if (pairing) CircularProgressIndicator()
            }
        } else {
            Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                Text("授权相机扫码")
            }
        }

        OutlinedButton(onClick = { showManual = true }) { Text("手动输入") }

        if (error != null) {
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }

    if (showManual) {
        ManualEntryDialog(
            onDismiss = { showManual = false },
            onSubmit = { endpoint, token, fp ->
                showManual = false
                onManual(endpoint, token, fp?.takeIf { it.isNotBlank() })
            },
        )
    }
}

@Composable
private fun ManualEntryDialog(
    onDismiss: () -> Unit,
    onSubmit: (endpoint: String, token: String, tlsFingerprint: String?) -> Unit,
) {
    var endpoint by remember { mutableStateOf("https://") }
    var token by remember { mutableStateOf("") }
    var tlsFp by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("手动配对") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "开发联调降级：输入 daemon 地址与 token（如 qaqh server --bind 输出的 token）。" +
                        "https 自签证书需填 tls_fp（/pairing/tokens 返回的指纹）。",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = endpoint,
                    onValueChange = { endpoint = it },
                    label = { Text("地址") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("token") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = tlsFp,
                    onValueChange = { tlsFp = it },
                    label = { Text("TLS 指纹（https 时必填）") },
                    placeholder = { Text("sha256:…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            val needsFp = endpoint.trim().startsWith("https")
            TextButton(
                onClick = { onSubmit(endpoint.trim().trimEnd('/'), token.trim(), tlsFp.trim()) },
                enabled = endpoint.startsWith("http") && token.isNotEmpty() && (!needsFp || tlsFp.isNotBlank()),
            ) { Text("配对") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** CameraX 预览 + ML Kit 二维码分析；每识别到一帧二维码回调一次。 */
@Composable
private fun QrCameraPreview(onQrText: (String) -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember { BarcodeScanning.getClient() }
    var lastText by remember { mutableStateOf<String?>(null) }

    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            executor.shutdown()
            scanner.close()
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val providerFuture = ProcessCameraProvider.getInstance(ctx)
            providerFuture.addListener({
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor) { image -> analyzeQr(scanner, image) { text ->
                    if (lastText != text) {
                        lastText = text
                        onQrText(text)
                    }
                } }
                runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                }
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        },
    )
}

private fun analyzeQr(
    scanner: com.google.mlkit.vision.barcode.BarcodeScanner,
    image: ImageProxy,
    onResult: (String) -> Unit,
) {
    @androidx.camera.core.ExperimentalGetImage
    val mediaImage = image.image
    if (mediaImage == null) {
        image.close()
        return
    }
    val input = InputImage.fromMediaImage(mediaImage, image.imageInfo.rotationDegrees)
    scanner.process(input)
        .addOnSuccessListener { barcodes ->
            barcodes.firstOrNull { it.valueType == Barcode.TYPE_TEXT }?.rawValue?.let(onResult)
        }
        .addOnCompleteListener { image.close() }
}

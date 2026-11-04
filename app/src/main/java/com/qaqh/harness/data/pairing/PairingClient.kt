package com.qaqh.harness.data.pairing

import com.qaqh.harness.core.ffi.pairDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 配对执行器(Rust core 版):
 * - [PairingPayload.Pairing] → Rust `pair_device`(POST /ringing/v2/pair,免鉴权)
 *   换 device_token;TLS pin 在 Rust 侧注入(tls_fp 透传);
 * - [PairingPayload.Direct] → 开发降级,凭据直接入库(连通性由连接层验证)。
 * 成功即返回要持久化的 [StoredCredentials]。
 */
class PairingClient(private val deviceName: String) {

    suspend fun pair(payload: PairingPayload): StoredCredentials = when (payload) {
        is PairingPayload.Direct -> withContext(Dispatchers.IO) { direct(payload) }
        is PairingPayload.Pairing -> withContext(Dispatchers.IO) { exchangePairingToken(payload) }
    }

    private fun direct(payload: PairingPayload.Direct): StoredCredentials = StoredCredentials(
        baseUrl = payload.baseUrl,
        token = payload.token,
        deviceId = "direct",
        deviceName = deviceName,
        pairedAtMs = System.currentTimeMillis(),
        tlsFingerprint = payload.tlsFingerprint,
    )

    private suspend fun exchangePairingToken(payload: PairingPayload.Pairing): StoredCredentials {
        val result = try {
            pairDevice(
                baseUrl = payload.baseUrl,
                pairingToken = payload.pairingToken,
                deviceName = deviceName,
                platform = "android",
                tlsFp = payload.tlsFingerprint,
            )
        } catch (e: com.qaqh.harness.core.ffi.CoreException) {
            throw PairingPayloadException("配对失败:${e.message ?: e::class.simpleName}")
        }
        return StoredCredentials(
            baseUrl = payload.baseUrl,
            token = result.deviceToken,
            deviceId = result.deviceId,
            deviceName = deviceName,
            pairedAtMs = System.currentTimeMillis(),
            scope = result.scope, // device 身份标记(view|interact|admin);Direct/手输为 null
            tlsFingerprint = payload.tlsFingerprint,
        )
    }
}

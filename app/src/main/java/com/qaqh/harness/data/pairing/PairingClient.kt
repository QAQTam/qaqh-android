package com.qaqh.harness.data.pairing

import com.qaqh.harness.data.ringing.RingingApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID

/**
 * 配对执行器：
 * - [PairingPayload.Pairing] → `POST /ringing/v2/pair` 换 device_token（依赖后端 M0 端点）；
 * - [PairingPayload.Direct] → 开发降级，直接用 token 调一次 open 校验连通性。
 * 成功即返回要持久化的 [StoredCredentials]。
 */
class PairingClient(private val deviceName: String) {

    @Serializable
    private data class PairRequest(
        val pairing_token: String,
        val device_name: String,
        val platform: String = "android",
        // 注意：请求体没有 scope_request 字段——档位由桌面端签发 pairing token 时定死
    )

    @Serializable
    private data class PairResponse(
        val device_token: String,
        val device_id: String,
        val scope: String? = null,
        val daemon_version: String? = null,
        val protocol_version: Int? = null,
    )

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    suspend fun pair(payload: PairingPayload): StoredCredentials = when (payload) {
        is PairingPayload.Direct -> withContext(Dispatchers.IO) { validateDirect(payload) }
        is PairingPayload.Pairing -> withContext(Dispatchers.IO) { exchangePairingToken(payload) }
    }

    private suspend fun validateDirect(payload: PairingPayload.Direct): StoredCredentials {
        // open 成功即视为连通且 token 有效
        RingingApi(payload.baseUrl, payload.token, pinnedHttpClient(payload.tlsFingerprint)).open(instanceId())
        return StoredCredentials(
            baseUrl = payload.baseUrl,
            token = payload.token,
            deviceId = UUID.randomUUID().toString(),
            deviceName = deviceName,
            pairedAtMs = System.currentTimeMillis(),
            tlsFingerprint = payload.tlsFingerprint,
        )
    }

    private suspend fun exchangePairingToken(payload: PairingPayload.Pairing): StoredCredentials {
        // M0 端点；未上线时 daemon 会返回 404，报错信息明确提示依赖后端进度
        val request = Request.Builder()
            .url(payload.baseUrl + "/ringing/v2/pair")
            .post(
                json.encodeToString(
                    PairRequest.serializer(),
                    PairRequest(pairing_token = payload.pairingToken, device_name = deviceName),
                ).toRequestBody("application/json".toMediaType())
            )
            .build()
        val http = pinnedHttpClient(payload.tlsFingerprint)
        val response = http.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val hint = if (resp.code == 404) "（/pair 端点尚不存在：后端 M0 未就绪）" else ""
                throw PairingPayloadException("配对失败 HTTP ${resp.code} $hint ${body.take(200)}")
            }
            json.decodeFromString(PairResponse.serializer(), body)
        }
        return StoredCredentials(
            baseUrl = payload.baseUrl,
            token = response.device_token,
            deviceId = response.device_id,
            deviceName = deviceName,
            pairedAtMs = System.currentTimeMillis(),
            scope = response.scope,  // device 身份标记（view|interact|admin）；Direct/手输为 null
            tlsFingerprint = payload.tlsFingerprint,
        )
    }

    private fun instanceId() = UUID.randomUUID().toString()
}

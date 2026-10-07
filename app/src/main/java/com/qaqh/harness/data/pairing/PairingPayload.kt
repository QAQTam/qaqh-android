package com.qaqh.harness.data.pairing

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 二维码配对 payload。主格式对齐后端建议稿 §3.2：
 * `{ "v":1, "kind":"qaqh-pair", "base_url":"https://<lan-ip>:64413",
 *    "pairing_token":"<one-time>", "tls_fp":"sha256:<hex>", "host_name":"..." }`
 *
 * 开发降级（M0 未就绪期间的临时形态，非正式协议）：
 * `qaqh://pair?v=1&endpoint=http://...&token=...`（直接携带可用 token）。
 */
sealed interface PairingPayload {

    /** 正式流程：一次性 pairing_token，需调 /ringing/v2/pair 换 device_token。 */
    data class Pairing(
        val baseUrl: String,
        val pairingToken: String,
        val tlsFingerprint: String?,
        val hostName: String?,
    ) : PairingPayload

    /** 开发降级：直接携带可用的 Bearer token；https 自签时需带 tls_fp。 */
    data class Direct(
        val baseUrl: String,
        val token: String,
        val tlsFingerprint: String? = null,
    ) : PairingPayload

    companion object {
        const val KIND = "qaqh-pair"
        const val SUPPORTED_VERSION = 1

        private val json = Json { ignoreUnknownKeys = true }

        /** 解析失败抛 [PairingPayloadException]；版本不识别同样抛。 */
        fun parse(raw: String): PairingPayload {
            val text = raw.trim()
            if (text.startsWith("{")) return parseJson(text)
            if (text.startsWith("qaqh://pair")) return parseUri(text)
            throw PairingPayloadException("无法识别的二维码内容")
        }

        private fun parseJson(text: String): PairingPayload {
            val obj = runCatching { json.parseToJsonElement(text) as? kotlinx.serialization.json.JsonObject }
                .getOrNull()
                ?: throw PairingPayloadException("二维码 JSON 无法解析")
            val kind = obj.str("kind")
            if (kind != KIND) throw PairingPayloadException("二维码类型不符（kind=$kind）")
            val v = obj.int("v") ?: throw PairingPayloadException("二维码缺少版本号")
            if (v != SUPPORTED_VERSION) throw PairingPayloadException("不支持的 payload 版本 v=$v")
            val baseUrl = obj.str("base_url") ?: throw PairingPayloadException("二维码缺少 base_url")
            val pairingToken = obj.str("pairing_token") ?: throw PairingPayloadException("二维码缺少 pairing_token")
            return Pairing(
                baseUrl = baseUrl.trimEnd('/'),
                pairingToken = pairingToken,
                tlsFingerprint = obj.str("tls_fp"),
                hostName = obj.str("host_name"),
            )
        }

        private fun parseUri(text: String): PairingPayload {
            val query = text.substringAfter('?', "")
            val params = query.split('&')
                .filter { it.contains('=') }
                .associate { it.substringBefore('=').to(it.substringAfter('=')) }
            val endpoint = params["endpoint"]?.let { java.net.URLDecoder.decode(it, Charsets.UTF_8) }
                ?: throw PairingPayloadException("二维码缺少 endpoint")
            val token = params["token"] ?: throw PairingPayloadException("二维码缺少 token")
            return Direct(baseUrl = endpoint.trimEnd('/'), token = token)
        }

        private fun Map<String, kotlinx.serialization.json.JsonElement>.str(key: String): String? =
            (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString || it.content.isNotEmpty() }?.content

        private fun Map<String, kotlinx.serialization.json.JsonElement>.int(key: String): Int? =
            (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()
    }
}

class PairingPayloadException(message: String) : Exception(message)

    /** 配对完成后本地保存的凭据。 */
    @Serializable
    data class StoredCredentials(
        val baseUrl: String,
        val token: String,
    /** 正式配对后的设备 id（/pair 返回）；开发降级为本地生成。 */
    val deviceId: String,
    val deviceName: String,
    val pairedAtMs: Long,
    /**
     * /pair 签发的权限档位（view|interact|admin）——**device 身份的标记**（非空即
     * 设备身份：审批走 challenge 流 /approvals/respond，canonical id 不出 daemon）。
     * Direct/手输配对为 null（admin token，审批走 canonical 命令通道）。
     */
    val scope: String? = null,
    /** daemon 证书指纹 sha256（https 自签时的信任锚）；null = 不 pin（明文 http）。 */
    val tlsFingerprint: String? = null,
)

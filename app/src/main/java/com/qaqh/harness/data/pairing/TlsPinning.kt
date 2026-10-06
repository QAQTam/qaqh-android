package com.qaqh.harness.data.pairing

import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * 自签证书信任：daemon 对非回环 bind 自动启用 rustls，自签证书系统不信任，
 * 信任根是配对 payload 里的 `tls_fp`。校验只做指纹比对，不做有效期/主机名
 * 校验——fp 即根。
 *
 * 兼容两种指纹语义（均为 daemon 所发叶子证书的属性，安全性等价）：
 * - sha256(整张 DER 证书)——daemon 当前实现（tls.rs `sha256_hex(leaf.as_ref())`，
 *   与其 types.rs 文档注释不符，待后端改齐）；
 * - sha256(SPKI)——建议稿文档语义（与 OkHttp CertificatePinner pin 对齐）。
 */
class SpkiPinnedTrustManager(private val expectedFingerprint: String) : X509TrustManager {

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
        // 不做客户端证书认证（daemon 是纯服务端 TLS）
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        val leaf = chain.firstOrNull() ?: throw CertificateException("empty certificate chain")
        val certSha256 = MessageDigest.getInstance("SHA-256").digest(leaf.encoded)
            .joinToString("") { "%02x".format(it) }
        val spkiSha256 = MessageDigest.getInstance("SHA-256").digest(leaf.publicKey.encoded)
            .joinToString("") { "%02x".format(it) }
        if (!fingerprintMatches(certSha256, expectedFingerprint) &&
            !fingerprintMatches(spkiSha256, expectedFingerprint)
        ) {
            throw CertificateException(
                "TLS 指纹不匹配：期望 $expectedFingerprint，" +
                    "实际 cert=$certSha256 spki=$spkiSha256（daemon 证书可能已重签，请重新配对）"
            )
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    companion object {
        /** 容忍 `sha256:` 前缀与大小写差异。 */
        fun fingerprintMatches(actual: String, expected: String): Boolean {
            val normalized = expected.removePrefix("sha256:").removePrefix("SHA256:").removePrefix("SHA-256:")
                .trim().lowercase()
            return normalized.isNotEmpty() && actual == normalized
        }
    }
}

/**
 * 按指纹构造信任自签证书的 OkHttpClient；fingerprint 为 null 时返回默认 client
 * （明文 HTTP 开发路径）。fp 是唯一的信任锚，因此主机名校验一并放宽。
 */
fun pinnedHttpClient(fingerprint: String?): OkHttpClient {
    if (fingerprint.isNullOrBlank()) return OkHttpClient()
    val trustManager = SpkiPinnedTrustManager(fingerprint)
    val sslContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<TrustManager>(trustManager), null)
    }
    return OkHttpClient.Builder()
        .sslSocketFactory(sslContext.socketFactory, trustManager)
        .hostnameVerifier(HostnameVerifier { _, _ -> true })
        .build()
}

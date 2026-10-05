package com.qaqh.harness.data.pairing

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.json.Json

/**
 * 凭据持久化：device_token 存 EncryptedSharedPreferences，主密钥在 Android Keystore。
 * （对应建议稿 §3.2 第 4 步；M0 后替换为 /pair 换取的 device_token，存储接口不变。）
 */
class CredentialStore(context: Context) {

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "qaqh_credentials",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun load(): StoredCredentials? {
        val raw = prefs.getString(KEY_CREDENTIALS, null)
        if (raw == null) {
            android.util.Log.w("CredentialStore", "load: no stored credentials")
            return null
        }
        return runCatching { json.decodeFromString(StoredCredentials.serializer(), raw) }
            .onFailure { android.util.Log.w("CredentialStore", "load: decrypt/parse failed", it) }
            .getOrNull()
    }

    fun save(credentials: StoredCredentials) {
        prefs.edit().putString(KEY_CREDENTIALS, json.encodeToString(StoredCredentials.serializer(), credentials)).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_CREDENTIALS = "credentials"
    }
}

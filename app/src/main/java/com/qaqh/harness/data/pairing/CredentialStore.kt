package com.qaqh.harness.data.pairing

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.json.Json

/**
 * 凭据持久化：主存 EncryptedSharedPreferences（主密钥在 Android Keystore），
 * 备份普通私有 prefs。测试机（Android 16 / OPPO）上 ESP 反复出现读回为空的
 * 已知稳定性问题（联调期间已复发 4 次），因此双写：读 ESP 为空而备份存在时
 * 回退备份并恢复进 ESP。M0 换 device_token 时存储接口不变。
 */
class CredentialStore(context: Context) {

    private val prefs: SharedPreferences = try {
        createEncrypted(context)
    } catch (e: Exception) {
        android.util.Log.w(TAG, "EncryptedSharedPreferences unavailable, fallback to plain prefs", e)
        context.getSharedPreferences(BACKUP_FILE, Context.MODE_PRIVATE)
    }

    /** 备份文件：ESP 不可靠时的兜底（仅 app 私有目录）。 */
    private val backup: SharedPreferences = context.getSharedPreferences(BACKUP_FILE, Context.MODE_PRIVATE)

    private val json = Json { ignoreUnknownKeys = true }

    fun load(): StoredCredentials? {
        val primary = readFrom(prefs)
        if (primary != null) return primary
        // ESP 读空但备份还在：恢复进 ESP 后用备份（ESP 空读复发时的自愈路径）
        val fromBackup = readFrom(backup) ?: run {
            android.util.Log.w(TAG, "load: no stored credentials")
            return null
        }
        android.util.Log.w(TAG, "load: ESP empty, restoring from backup prefs")
        runCatching { prefs.edit().putString(KEY_CREDENTIALS, json.encodeToString(StoredCredentials.serializer(), fromBackup)).apply() }
        return fromBackup
    }

    fun save(credentials: StoredCredentials) {
        val raw = json.encodeToString(StoredCredentials.serializer(), credentials)
        prefs.edit().putString(KEY_CREDENTIALS, raw).apply()
        backup.edit().putString(KEY_CREDENTIALS, raw).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
        backup.edit().clear().apply()
    }

    private fun readFrom(store: SharedPreferences): StoredCredentials? =
        store.getString(KEY_CREDENTIALS, null)?.let { raw ->
            runCatching { json.decodeFromString(StoredCredentials.serializer(), raw) }
                .onFailure { android.util.Log.w(TAG, "load: decrypt/parse failed", it) }
                .getOrNull()
        }

    private fun createEncrypted(context: Context): SharedPreferences =
        EncryptedSharedPreferences.create(
            context,
            "qaqh_credentials",
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )

    private companion object {
        const val TAG = "CredentialStore"
        const val KEY_CREDENTIALS = "credentials"
        const val BACKUP_FILE = "qaqh_credentials_backup"
    }
}

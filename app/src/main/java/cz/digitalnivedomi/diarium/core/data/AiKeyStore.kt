package cz.digitalnivedomi.diarium.core.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * The narrow storage slot behind [AiKeyStore]. Production uses
 * [EncryptedSecretSlot]; tests use a plain in-memory map, so the store's logic
 * (trimming, blank refusal, round-trip) is exercised on the JVM without the
 * Android Keystore.
 */
interface SecretSlot {
    fun read(): String?
    fun write(value: String)
    fun erase()
}

/**
 * Stores the user's own DeepSeek API key — the "bring your own key" of option C
 * (owner decision 2026-10-11).
 *
 * Where the key lives and where it does not:
 *  - It is written through [EncryptedSecretSlot] (AES-256; the master key sits in
 *    the Android Keystore, never in the preferences file itself).
 *  - The encrypted preferences file is excluded from Android auto-backup
 *    (`res/xml/backup_rules.xml` + `res/xml/data_extraction_rules.xml`): the
 *    Keystore master key is device-bound, so a restored copy could never be
 *    decrypted anyway — excluding the file keeps a dead blob out of any backup.
 *  - It travels only in requests to `api.deepseek.com` (the user's own
 *    generation calls). It is never sent to our server, never logged and never
 *    part of the APK — the user types it in.
 */
class AiKeyStore(private val slot: SecretSlot) {

    /** True when a usable key is stored. */
    fun hasKey(): Boolean = key() != null

    /** The stored key, or null when none is stored. Trimmed on the way out. */
    fun key(): String? = slot.read()?.trim()?.takeIf { it.isNotBlank() }

    /**
     * Persists [raw] (trimmed). A blank value is refused — callers show it as
     * "nothing saved", and no empty secret is ever written.
     */
    fun saveKey(raw: String): Boolean {
        val cleaned = raw.trim()
        if (cleaned.isBlank()) return false
        slot.write(cleaned)
        return true
    }

    /** Forgets the key. Any later generation falls back to the server route. */
    fun clear() = slot.erase()

    companion object {
        /** EncryptedSharedPreferences file name; auto-backup rules match this name. */
        const val FILE_NAME = "diarium_ai_secrets"

        /** The one preference the file holds. */
        const val KEY = "deepseek_api_key"

        /** The production store over EncryptedSharedPreferences. */
        fun forContext(context: Context): AiKeyStore = AiKeyStore(EncryptedSecretSlot(context))
    }
}

/**
 * EncryptedSharedPreferences-backed slot, created lazily so merely building the
 * store costs nothing: the first read/write pays for the Keystore handshake.
 * Reads/writes are additionally tolerant — a corrupted or undecryptable file
 * (restored from a backup, Keystore reset) surfaces as "no key", never as a
 * crash in the settings screen or a background worker.
 */
class EncryptedSecretSlot(context: Context) : SecretSlot {

    private val appContext = context.applicationContext

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            AiKeyStore.FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun read(): String? =
        runCatching { prefs.getString(AiKeyStore.KEY, null) }.getOrNull()

    override fun write(value: String) {
        prefs.edit().putString(AiKeyStore.KEY, value).apply()
    }

    override fun erase() {
        prefs.edit().remove(AiKeyStore.KEY).apply()
    }
}

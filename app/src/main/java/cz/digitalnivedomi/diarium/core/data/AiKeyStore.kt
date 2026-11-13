package cz.digitalnivedomi.diarium.core.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File

/** A read failure is not evidence that a key is absent. */
enum class LegacySecretPresence {
    PRESENT,
    ABSENT,
    UNKNOWN,
}

/**
 * The narrow storage slot behind [AiKeyStore]. Production uses
 * [EncryptedSecretSlot]; tests use a plain in-memory map, so the store's logic
 * (trimming, blank refusal, round-trip) is exercised on the JVM without the
 * Android Keystore.
 */
interface SecretSlot {
    fun read(ownerId: String): String?
    fun write(ownerId: String, value: String)
    fun erase(ownerId: String)

    /** Presence for an account; an adapter without a non-ambiguous probe is UNKNOWN. */
    fun valueStatus(ownerId: String): LegacySecretPresence = LegacySecretPresence.UNKNOWN

    /** Presence-only legacy inspection; an implementation without a safe probe is UNKNOWN. */
    fun legacyValueStatus(): LegacySecretPresence = LegacySecretPresence.UNKNOWN

    /**
     * Reads the ownerless legacy value for an explicit, user-confirmed adoption.
     * Implementations that cannot read it return null; nothing is deleted.
     */
    fun readLegacy(): String? = null
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
class AiKeyStore(private val slot: SecretSlot, private val ownerId: String?) {

    /** True when this account has a usable key stored. */
    fun hasKey(): Boolean = key() != null

    /** Presence-only status for Settings; unavailable ownership/read errors stay UNKNOWN. */
    fun keyStatus(): LegacySecretPresence {
        val owner = ownerId?.takeIf { it.isNotBlank() } ?: return LegacySecretPresence.UNKNOWN
        return runCatching { slot.valueStatus(owner) }.getOrDefault(LegacySecretPresence.UNKNOWN)
    }

    /** The current account's stored key, or null when ownership is unavailable. */
    fun key(): String? {
        val owner = ownerId?.takeIf { it.isNotBlank() } ?: return null
        return slot.read(owner)?.trim()?.takeIf { it.isNotBlank() }
    }

    /** Presence-only status; inspection failures must remain UNKNOWN. */
    fun legacyValueStatus(): LegacySecretPresence =
        runCatching { slot.legacyValueStatus() }.getOrDefault(LegacySecretPresence.UNKNOWN)

    /** Refuses writes without an authenticated account; a blank value never erases a key. */
    fun saveKey(raw: String): Boolean {
        val owner = ownerId?.takeIf { it.isNotBlank() } ?: return false
        val cleaned = raw.trim()
        if (cleaned.isBlank()) return false
        slot.write(owner, cleaned)
        return true
    }

    /** Removes only this account's key. Ownerless legacy bytes are never touched. */
    fun clear() {
        ownerId?.takeIf { it.isNotBlank() }?.let(slot::erase)
    }

    /** The ownerless legacy key, read only for an explicit adoption. Never logged or shown. */
    fun legacyKeyOrNull(): String? =
        runCatching { slot.readLegacy()?.trim()?.takeIf { it.isNotBlank() } }.getOrNull()

    /**
     * Copies the ownerless legacy key into this account after an explicit user
     * confirmation. It never overwrites an existing key, never runs without a
     * verified account and never deletes the legacy value.
     */
    fun adoptLegacyKey(): Boolean {
        val owner = ownerId?.takeIf { it.isNotBlank() } ?: return false
        if (key() != null) return false
        val legacy = legacyKeyOrNull() ?: return false
        return runCatching {
            slot.write(owner, legacy)
            true
        }.getOrDefault(false)
    }

    companion object {
        /** EncryptedSharedPreferences file name; auto-backup rules match this name. */
        const val FILE_NAME = "diarium_ai_secrets"

        /** Legacy ownerless preference. Kept for quarantine diagnostics only. */
        const val KEY = "deepseek_api_key"

        internal fun keyForOwner(ownerId: String): String = "${KEY}_v2_$ownerId"

        /** Production store scoped to the currently authenticated owner. */
        fun forContext(context: Context, ownerId: String?): AiKeyStore =
            AiKeyStore(EncryptedSecretSlot(context), ownerId?.takeIf { it.isNotBlank() })
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

    private val prefsDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
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
    private val prefs: SharedPreferences by prefsDelegate

    override fun read(ownerId: String): String? =
        runCatching { prefs.getString(AiKeyStore.keyForOwner(ownerId), null) }.getOrNull()

    override fun valueStatus(ownerId: String): LegacySecretPresence = try {
        if (prefs.getString(AiKeyStore.keyForOwner(ownerId), null)?.trim().isNullOrEmpty()) {
            LegacySecretPresence.ABSENT
        } else {
            LegacySecretPresence.PRESENT
        }
    } catch (_: Exception) {
        LegacySecretPresence.UNKNOWN
    }

    override fun write(ownerId: String, value: String) {
        prefs.edit().putString(AiKeyStore.keyForOwner(ownerId), value).apply()
    }

    override fun erase(ownerId: String) {
        prefs.edit().remove(AiKeyStore.keyForOwner(ownerId)).apply()
    }

    /**
     * Inspect only a store that an ordinary app operation has already opened.
     * Creating EncryptedSharedPreferences can generate Keystore material and
     * Tink keysets, so inventory must never force that initialization. A missing
     * or unopened file is deliberately UNKNOWN, not evidence that no key exists.
     */
    override fun legacyValueStatus(): LegacySecretPresence {
        val backingFile = File(
            File(appContext.applicationInfo.dataDir, "shared_prefs"),
            "${AiKeyStore.FILE_NAME}.xml",
        )
        if (!backingFile.isFile || !prefsDelegate.isInitialized()) {
            return LegacySecretPresence.UNKNOWN
        }
        return try {
            if (prefs.contains(AiKeyStore.KEY)) {
                LegacySecretPresence.PRESENT
            } else {
                LegacySecretPresence.ABSENT
            }
        } catch (_: Exception) {
            LegacySecretPresence.UNKNOWN
        }
    }

    /**
     * Reads the ownerless legacy key for an explicit adoption. The file must
     * already exist, so a read never creates Keystore material by itself.
     */
    override fun readLegacy(): String? {
        val backingFile = File(
            File(appContext.applicationInfo.dataDir, "shared_prefs"),
            "${AiKeyStore.FILE_NAME}.xml",
        )
        if (!backingFile.isFile) return null
        return runCatching { prefs.getString(AiKeyStore.KEY, null) }.getOrNull()
    }

}

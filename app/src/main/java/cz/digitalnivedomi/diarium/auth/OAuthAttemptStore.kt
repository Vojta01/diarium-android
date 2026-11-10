package cz.digitalnivedomi.diarium.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest

/** Pending native PKCE login. The verifier is null only after a successful exchange. */
internal data class OAuthAttempt(
    val state: String,
    val codeVerifier: String?,
    val redirectUri: String,
    val createdAtMillis: Long,
    val exchangeCompleted: Boolean = false,
    val ownerIdAtStart: String? = null,
    val hadSessionAtStart: Boolean = false,
)

/** Persistence seam so callback protocol tests never need Android Keystore or network. */
internal interface OAuthAttemptStore {
    fun save(attempt: OAuthAttempt): Boolean
    fun read(): OAuthAttempt?
    fun markExchangeCompleted(state: String): Boolean
    fun isExchangeCompleted(state: String): Boolean
    fun clearIfState(state: String): Boolean
}

/**
 * Stores the sole outstanding OAuth transaction in AndroidX encrypted preferences.
 * State and verifier are device-local ciphertext; the verifier is removed as soon
 * as the authorization-code exchange succeeds (or returns a terminal 4xx).
 */
internal class EncryptedOAuthAttemptStore(context: Context) : OAuthAttemptStore {
    private val appContext = context.applicationContext
    private val lock = Any()

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun save(attempt: OAuthAttempt): Boolean = synchronized(lock) {
        if (attempt.state.isBlank() || attempt.codeVerifier.isNullOrBlank() || attempt.redirectUri.isBlank()) {
            return@synchronized false
        }
        prefs.edit()
            .clear()
            .putString(KEY_STATE, attempt.state)
            .putString(KEY_VERIFIER, attempt.codeVerifier)
            .putString(KEY_REDIRECT_URI, attempt.redirectUri)
            .putLong(KEY_CREATED_AT, attempt.createdAtMillis)
            .putString(KEY_OWNER_AT_START, attempt.ownerIdAtStart)
            .putBoolean(KEY_HAD_SESSION_AT_START, attempt.hadSessionAtStart)
            .putBoolean(KEY_EXCHANGE_COMPLETED, false)
            .commit()
    }

    override fun read(): OAuthAttempt? = synchronized(lock) {
        try {
            val state = prefs.getString(KEY_STATE, null)?.takeIf(String::isNotBlank) ?: return@synchronized null
            val redirectUri = prefs.getString(KEY_REDIRECT_URI, null)?.takeIf(String::isNotBlank)
                ?: return@synchronized null
            val createdAt = if (prefs.contains(KEY_CREATED_AT)) prefs.getLong(KEY_CREATED_AT, 0L) else return@synchronized null
            val exchanged = prefs.getBoolean(KEY_EXCHANGE_COMPLETED, false)
            val verifier = prefs.getString(KEY_VERIFIER, null)?.takeIf(String::isNotBlank)
            val ownerAtStart = prefs.getString(KEY_OWNER_AT_START, null)?.takeIf(String::isNotBlank)
            val hadSessionAtStart = prefs.getBoolean(KEY_HAD_SESSION_AT_START, false)
            if (exchanged == (verifier != null)) return@synchronized null
            OAuthAttempt(
                state = state,
                codeVerifier = verifier,
                redirectUri = redirectUri,
                createdAtMillis = createdAt,
                exchangeCompleted = exchanged,
                ownerIdAtStart = ownerAtStart,
                hadSessionAtStart = hadSessionAtStart,
            )
        } catch (_: Exception) {
            null
        }
    }

    override fun markExchangeCompleted(state: String): Boolean = synchronized(lock) {
        val current = read() ?: return@synchronized false
        if (!sameState(state, current.state) || current.exchangeCompleted) return@synchronized false
        prefs.edit()
            .remove(KEY_VERIFIER)
            .putBoolean(KEY_EXCHANGE_COMPLETED, true)
            .commit()
    }

    override fun isExchangeCompleted(state: String): Boolean = synchronized(lock) {
        val current = read() ?: return@synchronized false
        current.exchangeCompleted && sameState(state, current.state)
    }

    override fun clearIfState(state: String): Boolean = synchronized(lock) {
        val current = read() ?: return@synchronized false
        if (!sameState(state, current.state)) return@synchronized false
        prefs.edit().clear().commit()
    }

    private fun sameState(first: String, second: String): Boolean =
        MessageDigest.isEqual(first.toByteArray(Charsets.UTF_8), second.toByteArray(Charsets.UTF_8))

    private companion object {
        const val FILE_NAME = "diarium_oauth_attempt"
        const val KEY_STATE = "state"
        const val KEY_VERIFIER = "code_verifier"
        const val KEY_REDIRECT_URI = "redirect_uri"
        const val KEY_CREATED_AT = "created_at_millis"
        const val KEY_OWNER_AT_START = "owner_id_at_start"
        const val KEY_HAD_SESSION_AT_START = "had_session_at_start"
        const val KEY_EXCHANGE_COMPLETED = "exchange_completed"
    }
}

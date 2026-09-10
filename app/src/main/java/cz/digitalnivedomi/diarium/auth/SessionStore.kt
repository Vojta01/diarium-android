package cz.digitalnivedomi.diarium.auth

import android.content.Context
import cz.digitalnivedomi.diarium.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Stores the Supabase session locally (SharedPreferences) so the native side
 * can (1) authenticate REST calls through [validAccessToken] and (2) authenticate
 * the /api/save-entry pushes from background WorkManager sync jobs.
 */
class SessionStore(context: Context) {

    private val prefs = context.getSharedPreferences("diarium_session", Context.MODE_PRIVATE)

    fun save(sessionJson: JSONObject) {
        prefs.edit().putString("session", sessionJson.toString()).apply()
    }

    fun sessionJson(): String? = prefs.getString("session", null)

    fun hasSession(): Boolean = !sessionJson().isNullOrEmpty()

    fun accessToken(): String? {
        val s = sessionJson() ?: return null
        return try {
            JSONObject(s).optString("access_token").takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    fun expiresAt(): Long {
        val s = sessionJson() ?: return 0L
        return try {
            JSONObject(s).optLong("expires_at", 0L)
        } catch (_: Exception) {
            0L
        }
    }

    /**
     * Returns an access token that is still valid for at least ~1 minute,
     * transparently refreshing it via Supabase when it has expired. Background
     * sync jobs MUST use this instead of [accessToken] — a raw stored token is
     * dead after ~1 h and every push would fail with 401 forever.
     */
    fun validAccessToken(): String? {
        val token = accessToken() ?: return null
        if (expiresAt() > System.currentTimeMillis() / 1000 + 60) return token
        return refreshAccessToken()
    }

    /**
     * Exchanges the stored refresh_token for a fresh access_token (Supabase
     * /auth/v1/token?grant_type=refresh_token) and persists the updated
     * session. Returns the new access token, or null on any failure.
     */
    fun refreshAccessToken(): String? {
        val s = sessionJson() ?: return null
        val refreshToken = try {
            JSONObject(s).optString("refresh_token")
        } catch (_: Exception) {
            ""
        }
        if (refreshToken.isBlank()) return null
        return try {
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
            val body = JSONObject().put("refresh_token", refreshToken).toString()
                .toRequestBody("application/json".toMediaType())
            val req = Request.Builder()
                .url("${BuildConfig.SUPABASE_URL}/auth/v1/token?grant_type=refresh_token")
                .header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                .header("Content-Type", "application/json")
                .post(body)
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val json = JSONObject(resp.body?.string() ?: return null)
                val newAccess = json.optString("access_token")
                if (newAccess.isBlank()) return null
                val newRefresh = json.optString("refresh_token").ifBlank { refreshToken }
                val expiresIn = json.optLong("expires_in", 3600)
                val updated = JSONObject().apply {
                    put("access_token", newAccess)
                    put("refresh_token", newRefresh)
                    put("expires_at", System.currentTimeMillis() / 1000 + expiresIn)
                    put("expires_in", expiresIn)
                    put("token_type", "bearer")
                    val user = JSONObject(s).optJSONObject("user")
                    if (user != null) put("user", user)
                }
                save(updated)
                newAccess
            }
        } catch (_: Exception) {
            null
        }
    }

    fun clear() {
        prefs.edit().remove("session").apply()
    }
}
package cz.digitalnivedomi.diarium.auth

import android.content.Context
import org.json.JSONObject

/**
 * Stores the Supabase session locally (SharedPreferences) so the native side
 * can (1) inject it into the WebView's localStorage and (2) authenticate
 * /api/save-entry pushes from background WorkManager sync jobs that have no
 * WebView access.
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

    fun clear() {
        prefs.edit().remove("session").apply()
    }
}
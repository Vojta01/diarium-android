package cz.digitalnivedomi.diarium.core.data

import cz.digitalnivedomi.diarium.auth.SessionStore
import org.json.JSONObject
import java.util.Base64

/**
 * Resolves the signed-in user's id from the persisted Supabase session.
 *
 * Kept here rather than on [SessionStore] so M2 leaves the auth layer untouched:
 * M1 already guarantees `user.id` (the JWT `sub`) is stored, and there is a
 * `sub`-claim fallback for sessions written by an older build.
 */
class SessionContext(private val sessionStore: SessionStore? = null) {

    fun userId(): String? {
        val raw = sessionStore?.sessionJson() ?: return null
        val json = try {
            JSONObject(raw)
        } catch (_: Exception) {
            return null
        }
        val id = json.optJSONObject("user")?.optString("id").orEmpty()
        if (id.isNotBlank()) return id
        return jwtSub(json.optString("access_token"))
    }

    companion object {
        /** Decodes the (unverified) `sub` claim — the user id, for RLS-scoped rows. */
        fun jwtSub(token: String): String? = try {
            val segment = token.split(".").getOrNull(1) ?: return null
            val padded = when (segment.length % 4) {
                2 -> "$segment=="
                3 -> "$segment="
                else -> segment
            }
            JSONObject(String(Base64.getUrlDecoder().decode(padded), Charsets.UTF_8))
                .optString("sub")
                .takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }
}

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

    /**
     * The signed-in user's display name as Supabase reports it
     * (`user_metadata.full_name`), or null when the stored session does not carry
     * it. Nothing is guessed from the email: this feeds the AI prompt, and
     * greeting someone by the local part of their address is worse than not
     * greeting them at all.
     */
    fun userName(): String? {
        val raw = sessionStore?.sessionJson() ?: return null
        val json = try {
            JSONObject(raw)
        } catch (_: Exception) {
            return null
        }
        return json.optJSONObject("user")
            ?.optJSONObject("user_metadata")
            ?.optString("full_name")
            ?.takeIf { it.isNotBlank() }
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

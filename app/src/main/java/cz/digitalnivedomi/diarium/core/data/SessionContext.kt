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
        val id = json.optJSONObject("user")?.plainString("id").orEmpty()
        if (id.isNotBlank()) return id
        return jwtSub(json.plainString("access_token"))
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
            ?.plainString("full_name")
            ?.takeIf { it.isNotBlank() }
    }

    /**
     * The signed-in user's access token, refreshed when it has expired (delegates to
     * [SessionStore.validAccessToken]), or null when there is no session. Data-layer
     * classes that authenticate a request against our own server — as opposed to
     * PostgREST, which a [SupabaseClient] already signs — need the token itself.
     */
    fun validAccessToken(): String? = sessionStore?.validAccessToken()

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
                .plainString("sub")
                .takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }
}

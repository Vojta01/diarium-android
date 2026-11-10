package cz.digitalnivedomi.diarium.core.data

import cz.digitalnivedomi.diarium.auth.SessionStore
import org.json.JSONObject
import java.util.Base64

/**
 * Resolves the owner pinned by the persisted session. New callback sessions use
 * the server-returned `verified_user_id`; older sessions retain their stored owner
 * locally for offline compatibility. Neither accessor verifies a fresh callback.
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

    /** Owner pin for authenticated gates, scheduled work and report dispatch. */
    fun verifiedUserId(): String? = sessionStore?.userSubject()

    /** Returns a live token only while the stored session still belongs to [ownerId]. */
    fun validAccessTokenForUser(ownerId: String): String? =
        sessionStore?.validAccessTokenForUser(ownerId)

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

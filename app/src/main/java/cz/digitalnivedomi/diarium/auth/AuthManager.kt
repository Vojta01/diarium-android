package cz.digitalnivedomi.diarium.auth

import android.app.Activity
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import cz.digitalnivedomi.diarium.core.data.HttpTransport
import cz.digitalnivedomi.diarium.core.data.OkHttpTransport
import cz.digitalnivedomi.diarium.fcm.FcmTokenRegistrar
import cz.digitalnivedomi.diarium.notifications.NotificationScheduler
import cz.digitalnivedomi.diarium.sync.SyncScheduler
import org.json.JSONObject
import java.util.Base64

/**
 * OAuth via Chrome Custom Tabs using Supabase Auth's authorization-code PKCE flow.
 *
 * Each sign-in gets an encrypted, one-time verifier and a random local state bound
 * to the exact `diarium://auth-callback` redirect. The callback is exchanged with
 * Supabase and its returned bearer token is checked by `/auth/v1/user` before any
 * saved session or account-scoped work is changed.
 */
class AuthManager(
    private val activity: Activity,
    private val sessionStore: SessionStore,
    /** Invoked only after Auth API verification and a successful session commit. */
    private val onSessionSaved: (() -> Unit)? = null,
    authTransport: HttpTransport = OkHttpTransport(),
) {
    private val callbackFlow = AuthPkceFlow(
        sessionStore = sessionStore,
        attempts = EncryptedOAuthAttemptStore(activity.applicationContext),
        transport = authTransport,
    )

    /** Launch the one-time PKCE OAuth flow in a Chrome Custom Tab. */
    fun startSignIn() {
        val target = callbackFlow.authorizationUrl()
        val builder = CustomTabsIntent.Builder()
        builder.setShowTitle(true)
        builder.build().launchUrl(activity, Uri.parse(target))
    }

    /**
     * Verify the callback and atomically persist the new session. A rejected callback
     * leaves a previously authenticated offline session and all local data intact.
     */
    suspend fun handleAuthCallback(uri: Uri): AuthCallbackOutcome {
        val outcome = callbackFlow.handleCallback(uri.toString())
        if (outcome !is AuthCallbackOutcome.Verified) return outcome

        onSessionSaved?.invoke()
        // Rebind account-scoped alarms and usage work only after the verified session is committed.
        NotificationScheduler.rescheduleAll(activity.applicationContext)
        SyncScheduler.onSessionChanged(activity.applicationContext, outcome.ownerId)
        FcmTokenRegistrar.register(activity.applicationContext)
        return outcome
    }

    companion object {
        /** Decodes claims only for consistency checks; these are not identity proof. */
        internal fun decodeJwtPayload(token: String): JSONObject? {
            return try {
                val segment = token.split(".").getOrNull(1) ?: return null
                val padded = when (segment.length % 4) {
                    2 -> "$segment=="
                    3 -> "$segment="
                    else -> segment
                }
                JSONObject(String(Base64.getUrlDecoder().decode(padded), Charsets.UTF_8))
            } catch (_: Exception) {
                null
            }
        }
    }
}

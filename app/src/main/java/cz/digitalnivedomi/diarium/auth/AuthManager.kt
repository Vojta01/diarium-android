package cz.digitalnivedomi.diarium.auth

import android.app.Activity
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import cz.digitalnivedomi.diarium.BuildConfig
import cz.digitalnivedomi.diarium.fcm.FcmTokenRegistrar
import cz.digitalnivedomi.diarium.sync.UsageSyncWorker
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.util.Base64

/**
 * OAuth via Chrome Custom Tabs.
 *
 * Google blocks OAuth in embedded WebViews, so we open the Supabase authorize
 * URL in a real browser tab. The redirect is `diarium://auth-callback#...`
 * (registered in Supabase → Authentication → Redirect URLs), which Android
 * routes back to [AuthCallbackActivity] → [MainActivity]. The tokens are then
 * persisted in [SessionStore] for the native app (UI + background workers); there
 * is no WebView and no localStorage mirror anymore.
 *
 * The parsing half ([parseSessionFromCallback]) is deliberately free of Android
 * APIs so it can be exercised by a JVM unit test.
 */
class AuthManager(
    private val activity: Activity,
    private val sessionStore: SessionStore,
    /** Invoked right after a successful session save (used to flip the Compose gate). */
    private val onSessionSaved: (() -> Unit)? = null,
) {

    /** Launch the OAuth flow in a Chrome Custom Tab. */
    fun startSignIn(originalUrl: String? = null) {
        val target = if (originalUrl != null) {
            // Reuse an incoming authorize URL but swap redirect_to to our deep link,
            // so tokens arrive at diarium://auth-callback where we can capture them.
            val uri = Uri.parse(originalUrl)
            val builder = uri.buildUpon().clearQuery()
            val queryParams = ArrayList<Pair<String, String?>>()
            uri.queryParameterNames.forEach { name ->
                if (name != "redirect_to") {
                    queryParams.add(name to uri.getQueryParameter(name))
                }
            }
            queryParams.forEach { (k, v) -> builder.appendQueryParameter(k, v ?: "") }
            builder.appendQueryParameter(
                "redirect_to",
                "${BuildConfig.AUTH_SCHEME}://${BuildConfig.AUTH_HOST}"
            ).build().toString()
        } else {
            oauthUrl()
        }

        val builder = CustomTabsIntent.Builder()
        builder.setShowTitle(true)
        val customTabsIntent = builder.build()
        customTabsIntent.launchUrl(activity, Uri.parse(target))
    }

    /**
     * Called from MainActivity when the deep link arrives.
     * Parses `#access_token=…&refresh_token=…&expires_in=…`, stores the session,
     * then kicks off the usage backfill and (re)registers the FCM token.
     *
     * @return true when a session was parsed and saved.
     */
    fun handleAuthCallback(uri: Uri): Boolean {
        val session = parseSessionFromCallback(uri.toString()) ?: return false

        sessionStore.save(session)
        onSessionSaved?.invoke()

        // Kick off an immediate usage backfill now that we are authenticated,
        // so the last 7 days appear in the chart right away (not only today).
        WorkManager.getInstance(activity).enqueue(
            OneTimeWorkRequestBuilder<UsageSyncWorker>()
                .setInputData(Data.Builder().putString("mode", "backfill").build())
                .build()
        )

        // Re-register the FCM token now that we have a session to attach it to.
        FcmTokenRegistrar.register(activity.applicationContext)
        return true
    }

    companion object {

        /** URL for Supabase OAuth (implicit flow → tokens in URL hash). */
        internal fun oauthUrl(): String {
            return "${BuildConfig.SUPABASE_URL}/auth/v1/authorize" +
                "?provider=google" +
                "&redirect_to=${URLEncoder.encode("${BuildConfig.AUTH_SCHEME}://${BuildConfig.AUTH_HOST}", Charsets.UTF_8.name())}"
        }

        /**
         * Pure (Android-free) deep-link parsing:
         * `diarium://auth-callback#access_token=<jwt>&refresh_token=r1&expires_in=3600`
         * → the Supabase session JSON stored by [SessionStore], including
         * `expires_at` (epoch seconds) and the decoded JWT user (`sub`, `email`).
         *
         * Returns null when the URI carries no usable session.
         */
        internal fun parseSessionFromCallback(uriString: String): JSONObject? {
            val hashIndex = uriString.indexOf('#')
            if (hashIndex < 0) return null
            val fragment = uriString.substring(hashIndex + 1)
            if (fragment.isBlank()) return null

            val params = fragment.split("&")
                .mapNotNull { kv ->
                    val parts = kv.split("=", limit = 2)
                    if (parts.size == 2) parts[0] to percentDecode(parts[1]) else null
                }
                .toMap()

            val accessToken = params["access_token"]?.takeIf { it.isNotBlank() } ?: return null
            val refreshToken = params["refresh_token"].orEmpty()
            val expiresIn = params["expires_in"]?.toLongOrNull() ?: 3600L
            val expiresAt = (System.currentTimeMillis() / 1000) + expiresIn

            // Basic user info comes from the JWT payload (the implicit flow's hash
            // never carries a `user` object — see auth-callback.html on the web side).
            val payload = decodeJwtPayload(accessToken)
            val user = JSONObject().apply {
                put("id", payload?.optString("sub").orEmpty())
                put("email", payload?.optString("email").orEmpty())
                put("aud", payload?.optString("aud").orEmpty())
                put("role", payload?.optString("role").orEmpty())
            }

            return JSONObject().apply {
                put("access_token", accessToken)
                put("refresh_token", refreshToken)
                put("expires_at", expiresAt)
                put("expires_in", expiresIn)
                put("token_type", "bearer")
                put("user", user)
            }
        }

        /** Decodes the (unverified) JWT payload — used only for display fields. */
        internal fun decodeJwtPayload(token: String): JSONObject? = try {
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

        /**
         * Percent-decodes a URI fragment value. Deliberately not
         * `URLDecoder.decode`, which would turn a literal `+` into a space —
         * wrong for fragments and able to corrupt a refresh token.
         */
        internal fun percentDecode(value: String): String {
            if ('%' !in value) return value
            val bytes = ByteArrayOutputStream(value.length)
            var i = 0
            while (i < value.length) {
                val c = value[i]
                if (c == '%' && i + 2 < value.length) {
                    val hi = Character.digit(value[i + 1], 16)
                    val lo = Character.digit(value[i + 2], 16)
                    if (hi >= 0 && lo >= 0) {
                        bytes.write((hi shl 4) or lo)
                        i += 3
                        continue
                    }
                }
                bytes.write(c.toString().toByteArray(Charsets.UTF_8))
                i++
            }
            return String(bytes.toByteArray(), Charsets.UTF_8)
        }
    }
}

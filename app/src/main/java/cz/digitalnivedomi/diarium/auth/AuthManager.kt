package cz.digitalnivedomi.diarium.auth

import android.content.Intent
import android.net.Uri
import android.webkit.WebView
import androidx.browser.customtabs.CustomTabsIntent
import cz.digitalnivedomi.diarium.BuildConfig
import org.json.JSONObject

/**
 * OAuth via Chrome Custom Tabs.
 *
 * Google blocks OAuth in embedded WebViews, so we open the Supabase authorize
 * URL in a real browser tab. The redirect is `diarium://auth-callback#...`
 * (registered in Supabase → Authentication → Redirect URLs), which Android
 * routes back to [AuthCallbackActivity]. There the tokens are saved and
 * mirrored into the WebView's localStorage, then the web app reloads.
 */
class AuthManager(
    private val activity: android.app.Activity,
    private val webView: WebView,
    private val sessionStore: SessionStore
) {
    /** URL for Supabase OAuth (implicit flow → tokens in URL hash). */
    private fun oauthUrl(): String {
        return "${BuildConfig.SUPABASE_URL}/auth/v1/authorize" +
            "?provider=google" +
            "&redirect_to=${Uri.encode("${BuildConfig.AUTH_SCHEME}://${BuildConfig.AUTH_HOST}")}"
    }

    fun startSignIn() {
        val builder = CustomTabsIntent.Builder()
        builder.setShowTitle(true)
        val customTabsIntent = builder.build()
        customTabsIntent.launchUrl(activity, Uri.parse(oauthUrl()))
    }

    /**
     * Called from MainActivity when the deep link arrives.
     * Parses `#access_token=...&refresh_token=...&expires_in=...`, stores the
     * session, injects it into WebView localStorage and reloads the page.
     */
    fun handleAuthCallback(uri: Uri) {
        val fragment = uri.fragment ?: return
        val params = fragment.split("&")
            .mapNotNull { kv ->
                val parts = kv.split("=", limit = 2)
                if (parts.size == 2) parts[0] to Uri.decode(parts[1]) else null
            }
            .toMap()

        val accessToken = params["access_token"] ?: return
        val refreshToken = params["refresh_token"] ?: ""
        val expiresIn = params["expires_in"]?.toLongOrNull() ?: 3600
        val expiresAt = (System.currentTimeMillis() / 1000) + expiresIn

        // Try to decode basic user info from the JWT payload.
        val user = try {
            val payload = accessToken.split(".").getOrNull(1) ?: ""
            val decoded = String(android.util.Base64.decode(payload, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP))
            val json = JSONObject(decoded)
            JSONObject().apply {
                put("id", json.optString("sub"))
                put("email", json.optString("email"))
                put("aud", json.optString("aud"))
                put("role", json.optString("role"))
            }
        } catch (_: Exception) {
            JSONObject().apply { put("id", "") }
        }

        val session = JSONObject().apply {
            put("access_token", accessToken)
            put("refresh_token", refreshToken)
            put("expires_at", expiresAt)
            put("expires_in", expiresIn)
            put("token_type", "bearer")
            put("user", user)
        }

        sessionStore.save(session)

        // Mirror into WebView localStorage using the same key the web app uses.
        val localStorageKey = "sb-${BuildConfig.SUPABASE_REF}-auth-token"
        val escaped = session.toString().replace("\\", "\\\\").replace("'", "\\'")
        webView.post {
            webView.evaluateJavascript(
                "localStorage.setItem('$localStorageKey', '$escaped');" +
                    "window.location.reload();",
                null
            )
        }
    }
}
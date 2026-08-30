package cz.digitalnivedomi.diarium.webview

import android.app.Activity
import android.webkit.JavascriptInterface
import cz.digitalnivedomi.diarium.auth.SessionStore
import cz.digitalnivedomi.diarium.stats.UsageStatsProvider
import org.json.JSONObject

/**
 * Bridge exposed to the Diarium web app as `window.AndroidBridge`.
 * The web app checks `typeof window.AndroidBridge !== "undefined"` to detect
 * it runs inside the native wrapper and calls:
 *   AndroidBridge.readUsageStats(date) → JSON string
 *   AndroidBridge.getUsageAccess()     → true/false
 *   AndroidBridge.openUsageAccessSettings()
 *   AndroidBridge.getSession()         → JSON string or null (for save-entry auth)
 */
class BridgeJavaScriptInterface(
    private val activity: Activity,
    private val sessionStore: SessionStore
) {
    private val usageStats = UsageStatsProvider(activity.applicationContext)

    @JavascriptInterface
    fun readUsageStats(date: String): String {
        return try {
            val stats = usageStats.dayStats(date)
            val sessionInfo = if (sessionStore.hasSession()) {
                JSONObject().apply { put("hasSession", true) }
            } else {
                JSONObject().apply { put("hasSession", false) }
            }
            stats.put("session", sessionInfo)
            stats.toString()
        } catch (e: Exception) {
            JSONObject().apply {
                put("error", e.message ?: "usage stats error")
                put("available", usageStats.hasUsageAccess())
            }.toString()
        }
    }

    @JavascriptInterface
    fun getUsageAccess(): Boolean = usageStats.hasUsageAccess()

    @JavascriptInterface
    fun openUsageAccessSettings() {
        activity.runOnUiThread { usageStats.openUsageAccessSettings() }
    }

    /** Session JSON as stored for the web app (same shape the web uses). */
    @JavascriptInterface
    fun getSession(): String? = sessionStore.sessionJson()
}
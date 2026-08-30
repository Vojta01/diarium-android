package cz.digitalnivedomi.diarium

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import cz.digitalnivedomi.diarium.auth.AuthManager
import cz.digitalnivedomi.diarium.auth.SessionStore
import cz.digitalnivedomi.diarium.stats.UsageStatsProvider
import cz.digitalnivedomi.diarium.sync.SyncScheduler
import cz.digitalnivedomi.diarium.webview.BridgeJavaScriptInterface

/**
 * Diarium — thin native wrapper around the Diarium web app.
 * - Renders the existing web app in a WebView (same UX, same Supabase).
 * - Exposes [BridgeJavaScriptInterface] so the web app can read accurate
 *   per-app usage statistics via UsageStatsManager.
 * - OAuth via Chrome Custom Tabs + diarium:// deep link; session mirrored
 *   into WebView localStorage.
 */
class MainActivity : AppCompatActivity() {

    lateinit var webView: WebView
    private val sessionStore by lazy { SessionStore(this) }
    private val usageStats by lazy { UsageStatsProvider(this) }
    private val authManager by lazy { AuthManager(this, webView, sessionStore) }

    private var pendingFileCallback: ValueCallback<Array<Uri>>? = null

    companion object {
        const val REQUEST_FILE = 1001
        const val REQUEST_NOTIFICATIONS = 1002
        private const val PREFS = "diarium_onboarding"
        private const val KEY_USAGE_PROMPTED = "usage_prompted"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        setContentView(webView)

        // Seed the daily sync jobs on first launch (21:00 snapshot, 07:00 backfill).
        SyncScheduler.ensureScheduled(this)

        val client = DiariumWebViewClient()
        webView.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.allowFileAccess = false
            settings.mediaPlaybackRequiresUserGesture = false
            settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
            @Suppress("DEPRECATION")
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
                WebSettingsCompat.setForceDark(settings, WebSettingsCompat.FORCE_DARK_OFF)
            }
            webViewClient = client
            webChromeClient = DiariumChromeClient()
            addJavascriptInterface(BridgeJavaScriptInterface(this@MainActivity, sessionStore), "AndroidBridge")
            loadUrl(BuildConfig.DIARIUM_URL)
        }

        // After first successful page load, mirror any stored session into
        // WebView localStorage (typed in by the web app on boot).
        client.onPageFinished = { view ->
            sessionStore.sessionJson()?.let { session ->
                view.evaluateJavascript("window.__diariumInjectSession && window.__diariumInjectSession($session)", null)
            }
            // Ask for notification permission (Android 13+) and usage access
            // once the app UI is visible — not earlier.
            if (!permissionsRequested) {
                permissionsRequested = true
                requestNotificationPermissionIfNeeded()
            }
            checkUsageAccessAndPrompt()
        }

        // Auth deep link may arrive on cold start.
        intent?.let { handleIntent(it) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        val uri: Uri? = intent.data
        if (uri != null && uri.scheme == BuildConfig.AUTH_SCHEME && uri.host == BuildConfig.AUTH_HOST) {
            authManager.handleAuthCallback(uri)
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
            webView.goBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_FILE) {
            val callback = pendingFileCallback ?: return
            pendingFileCallback = null
            val results = if (resultCode == RESULT_OK && data?.data != null) {
                arrayOf(data.data!!)
            } else {
                null
            }
            callback.onReceiveValue(results)
        }
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    inner class DiariumWebViewClient : WebViewClient() {
        var onPageFinished: ((WebView) -> Unit)? = null

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url
            val urlStr = url.toString()
            // Supabase OAuth: open in Chrome Custom Tab (Google blocks OAuth inside
            // embedded WebViews). The redirect diarium://auth-callback returns here.
            if (urlStr.contains("/auth/v1/authorize") || urlStr.contains("accounts.google.com/o/oauth2")) {
                authManager.startSignIn(urlStr)
                return true
            }
            if (url.scheme == BuildConfig.AUTH_SCHEME) {
                authManager.handleAuthCallback(url)
                return true
            }
            if (url.scheme == "https" || url.scheme == "http") return false
            try {
                startActivity(Intent(Intent.ACTION_VIEW, url))
            } catch (_: Exception) {
            }
            return true
        }

        override fun onPageFinished(view: WebView, url: String?) {
            super.onPageFinished(view, url)
            onPageFinished?.invoke(view)
        }
    }

    inner class DiariumChromeClient : WebChromeClient() {
        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams
        ): Boolean {
            pendingFileCallback?.onReceiveValue(null)
            pendingFileCallback = filePathCallback
            try {
                startActivityForResult(fileChooserParams.createIntent(), REQUEST_FILE)
            } catch (_: Exception) {
                pendingFileCallback = null
                filePathCallback.onReceiveValue(null)
                return false
            }
            return true
        }
    }

    fun openUsageAccessSettings() {
        usageStats.openUsageAccessSettings()
    }

    private var permissionsRequested = false

    /** Android 13+ (API 33) requires a runtime request for notifications. */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                requestPermissions(
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_NOTIFICATIONS
                )
            }
        }
    }

    /**
     * Onboarding for usage access. Called after first page load and after the
     * user returns from Settings (onStart). Shows a dialog that either opens
     * Settings or — if the system permanently recorded a denial — explains
     * that a one-time uninstall + reinstall is required.
     */
    private fun checkUsageAccessAndPrompt() {
        if (usageStats.hasUsageAccess()) {
            // Grant arrived → remember we prompted so we don't nag.
            prefs().edit().remove(KEY_USAGE_PROMPTED).apply()
            return
        }
        val prefs = prefs()
        if (prefs.getBoolean(KEY_USAGE_PROMPTED, false)) return // asked once already
        prefs.edit().putBoolean(KEY_USAGE_PROMPTED, true).apply()

        val state = usageStats.usageAccessState()
        val title: String
        val message: String
        val actionLabel: String
        if (state == "denied") {
            title = "Systém odepřel přístup ke statistikám"
            message = "Android si pamatuje zamítnutí přístupu k údajům o využití a přepínač " +
                "je proto šedý a neklikatelný. Stačí jednou odinstalovat Diarium a znovu ho " +
                "nainstalovat — pak přístup povolíš normálně.\n\n" +
                "Bez přístupu k údajům o využití chybí statistiky času v aplikacích."
            actionLabel = "Porozuměl jsem"
        } else {
            title = "Povol statistiky používání telefonu"
            message = "Aby Diarium zobrazovalo přesné statistiky (čas v aplikacích, odemknutí), " +
                "potřebuje přístup k údajům o využití — stejné oprávnění, jaké se zobrazuje " +
                "v Digitální rovnováze.\n\n1. Klepni na „Otevřít nastavení\"\n" +
                "2. Najdi Diarium a zapni přepínač"
            actionLabel = "Otevřít nastavení"
        }

        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(actionLabel) { _, _ ->
                if (state != "denied") usageStats.openUsageAccessSettings()
            }
            .setNegativeButton("Později", null)
            .setCancelable(true)
            .show()
    }

    override fun onStart() {
        super.onStart()
        // Re-check after returning from Settings / re-entering the app.
        if (!usageStats.hasUsageAccess()) {
            prefs().edit().remove(KEY_USAGE_PROMPTED).apply() // allow re-prompt
        }
    }

    private fun prefs() = getSharedPreferences(PREFS, MODE_PRIVATE)
}
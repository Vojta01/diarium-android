package cz.digitalnivedomi.diarium

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
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
            if (url.scheme == "https" || url.scheme == "http") return false
            if (url.scheme == BuildConfig.AUTH_SCHEME) {
                authManager.handleAuthCallback(url)
                return true
            }
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
}
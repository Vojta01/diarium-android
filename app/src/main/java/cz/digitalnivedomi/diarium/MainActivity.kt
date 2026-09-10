package cz.digitalnivedomi.diarium

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import cz.digitalnivedomi.diarium.auth.AuthManager
import cz.digitalnivedomi.diarium.auth.AuthStateHolder
import cz.digitalnivedomi.diarium.auth.SessionStore
import cz.digitalnivedomi.diarium.fcm.FcmTokenRegistrar
import cz.digitalnivedomi.diarium.notifications.NotificationScheduler
import cz.digitalnivedomi.diarium.sync.SyncScheduler
import cz.digitalnivedomi.diarium.ui.DiariumApp
import cz.digitalnivedomi.diarium.ui.theme.DiariumTheme

/**
 * Single-activity host. The UI is entirely Compose; this class wires up the
 * background plumbing recycled from the WebView wrapper (usage sync, passive
 * notifications, FCM token) and owns the auth objects so the Compose tree stays
 * free of Activity references.
 */
class MainActivity : ComponentActivity() {

    private lateinit var sessionStore: SessionStore
    private lateinit var authState: AuthStateHolder
    private lateinit var authManager: AuthManager

    /** `diarium://auth-callback#access_token=…` handed up to the auth layer. */
    private val authDeepLink = mutableStateOf<String?>(null)

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* reminder opt-in only */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        sessionStore = SessionStore(this)
        authState = AuthStateHolder(sessionStore)
        // onSessionSaved flips the Compose gate; a cold start with a stored
        // session is already AUTHENTICATED from the SessionStore.
        authManager = AuthManager(this, sessionStore, onSessionSaved = { authState.refresh() })

        // --- Recycled background plumbing (unchanged behaviour) --------------
        SyncScheduler.ensureScheduled(this)
        NotificationScheduler.rescheduleAll(this)
        FcmTokenRegistrar.register(this)
        requestNotificationPermissionIfNeeded()

        authDeepLink.value = intent?.dataString

        setContent {
            DiariumTheme {
                DiariumApp(
                    authDeepLink = authDeepLink,
                    authState = authState,
                    onSignIn = { authManager.startSignIn() },
                    onAuthDeepLink = { link -> authManager.handleAuthCallback(Uri.parse(link)) },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // A deep link arriving while the app is already open (e.g. OAuth return).
        authDeepLink.value = intent.dataString
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

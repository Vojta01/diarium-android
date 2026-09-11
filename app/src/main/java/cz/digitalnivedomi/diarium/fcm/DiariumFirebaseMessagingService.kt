package cz.digitalnivedomi.diarium.fcm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import cz.digitalnivedomi.diarium.MainActivity
import cz.digitalnivedomi.diarium.R
import cz.digitalnivedomi.diarium.core.data.PushTokensRepository
import cz.digitalnivedomi.diarium.ui.nav.Routes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Native FCM push:
 *  - keeps the FCM token in sync with Supabase `push_tokens` under the signed-in
 *    account (RLS-scoped upsert with the user's own JWT, no server hop);
 *  - displays incoming push (a finished weekly/monthly AI report) on the **same**
 *    `diarium_reminders` channel the local reminders use, so the user sees one
 *    channel in system settings instead of two identically named ones
 *    (`diarium_alerts` is no longer created).
 */
class DiariumFirebaseMessagingService : FirebaseMessagingService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        registerToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val title = message.notification?.title ?: "Diarium"
        val body = message.notification?.body ?: return
        // The server may target a route; otherwise the tap opens the dashboard.
        val route = message.data[EXTRA_OPEN_ROUTE] ?: Routes.HOME
        showNotification(title, body, route)
    }

    /**
     * The token rotated (or arrived for the first time). Same upsert the registrar
     * performs — done here too because `onNewToken` is the only callback that runs
     * while the app is closed, and the new token must replace the old one under the
     * current account.
     */
    private fun registerToken(token: String) {
        val appContext = applicationContext
        scope.launch {
            // Silent: with no session there is nothing to attach the token to, and
            // the next app start (or login) retries the registration.
            PushTokensRepository(appContext).register(token)
        }
    }

    private fun showNotification(title: String, body: String, route: String) {
        ensureRemindersChannel()

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_OPEN_ROUTE, route)
        }
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_diarium)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted — the push is legitimately invisible.
        }
    }

    /**
     * Creates the reminders channel if the local scheduler has not run yet. Same id,
     * name and importance as `NotificationWorker.showNotification()`, so the two
     * paths share one channel and repeated creation is a no-op.
     */
    private fun ensureRemindersChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_REMINDERS,
                "Diarium připomenutí",
                NotificationManager.IMPORTANCE_DEFAULT,
            )
        )
    }

    private companion object {
        /**
         * The single reminders channel, shared with the local `NotificationWorker`.
         * `diarium_alerts` (the old FCM-only id) is deliberately never recreated —
         * an already-existing system entry for it may linger, but no new push
         * lands there.
         */
        const val CHANNEL_REMINDERS = "diarium_reminders"

        /**
         * Tap extra the navigation host reads to deep-link the dashboard. The
         * literal matches `NotificationIntents.EXTRA_OPEN_ROUTE` (M6 branch A); the
         * name must not diverge, and this branch must not create that file.
         */
        const val EXTRA_OPEN_ROUTE = "open_route"

        /** Stable id so a newer push replaces the previous one instead of stacking. */
        const val NOTIFICATION_ID = 1001
    }
}

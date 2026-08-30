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
import cz.digitalnivedomi.diarium.BuildConfig
import cz.digitalnivedomi.diarium.MainActivity
import cz.digitalnivedomi.diarium.R
import cz.digitalnivedomi.diarium.auth.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Native FCM push:
 *  - keeps the FCM token in sync with the Diarium backend (Supabase push_tokens)
 *  - displays native notifications for check-in reminders & AI reports
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
        val deepLink = message.data["url"]
        showNotification(title, body, deepLink)
    }

    private fun registerToken(token: String) {
        scope.launch {
            try {
                val session = SessionStore(applicationContext)
                val accessToken = session.accessToken() ?: return@launch
                val client = OkHttpClient()
                val body = JSONObject().apply {
                    put("platform", "android")
                    put("fcm_token", token)
                }.toString()
                val request = Request.Builder()
                    .url("${BuildConfig.DIARIUM_URL}/api/push/subscribe")
                    .header("Authorization", "Bearer $accessToken")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(request).execute().use { resp ->
                    // 200 = stored. 401 = session expired (tokens rotate later).
                    if (resp.code == 200) {
                        // remember that we have a token so we can unregister on logout
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun showNotification(title: String, body: String, deepLink: String?) {
        val channelId = "diarium_alerts"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Diarium připomenutí", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            deepLink?.let { putExtra("open_url", it) }
        }
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_stat_diarium)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()

        // Use a real icon if we ship one; else the app icon via resources
        // (small icon must be a plain drawable; keep it minimal in V1).
        try {
            NotificationManagerCompat.from(this).notify(1001, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted
        }
    }
}
package cz.digitalnivedomi.diarium.fcm

import android.content.Context
import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import cz.digitalnivedomi.diarium.BuildConfig
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
 * Explicitly fetches the FCM token and registers it with the Diarium backend.
 * Called from MainActivity on startup and after login — `onNewToken` alone is
 * not guaranteed to fire right after install.
 */
object FcmTokenRegistrar {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient()

    fun register(context: Context) {
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token ->
                if (token.isNotBlank()) {
                    pushToken(context.applicationContext, token)
                }
            }
            .addOnFailureListener { e ->
                Log.w("DiariumFCM", "FCM token fetch failed: ${e.message}")
            }
    }

    private fun pushToken(context: Context, token: String) {
        scope.launch {
            try {
                val session = SessionStore(context)
                val accessToken = session.accessToken() ?: return@launch
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
                    Log.i("DiariumFCM", "Token registration → HTTP ${resp.code}")
                }
            } catch (e: Exception) {
                Log.w("DiariumFCM", "Token registration failed: ${e.message}")
            }
        }
    }
}
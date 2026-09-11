package cz.digitalnivedomi.diarium.fcm

import android.content.Context
import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import cz.digitalnivedomi.diarium.core.data.PushTokensRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Explicitly fetches the FCM token and stores it in Supabase `push_tokens` under
 * the signed-in account. Called from MainActivity once there is a session (and
 * from AuthManager right after login) — `onNewToken` alone is not guaranteed to
 * fire right after install.
 *
 * The write is a plain PostgREST upsert with the user's own JWT
 * ([PushTokensRepository]); the old `/api/push/subscribe` Vercel hop with a
 * service-role key is gone.
 */
object FcmTokenRegistrar {

    private const val TAG = "DiariumFCM"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Fetch the device token and register it; a no-op until the user signs in. */
    fun register(context: Context) {
        val appContext = context.applicationContext
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token ->
                if (token.isNotBlank()) pushToken(appContext, token)
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "FCM token fetch failed: ${e.message}")
            }
    }

    /**
     * Upserts [token] for the signed-in user. Silent by design: a token that
     * cannot be stored (no session yet, offline) is retried on the next start, and
     * a failure here must never surface as a crash in the Activity or the service.
     */
    private fun pushToken(context: Context, token: String) {
        scope.launch {
            if (PushTokensRepository(context).register(token)) {
                Log.i(TAG, "FCM token stored in push_tokens")
            } else {
                Log.w(TAG, "FCM token not stored (no session, or the request failed)")
            }
        }
    }
}

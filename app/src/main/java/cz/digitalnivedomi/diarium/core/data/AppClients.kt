package cz.digitalnivedomi.diarium.core.data

import android.content.Context
import cz.digitalnivedomi.diarium.auth.SessionStore
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Process-wide shared clients — the app's small stand-in for a DI container.
 *
 * WHY this exists: every `SupabaseClient` used to build its own [OkHttpClient]
 * (its own connection pool and dispatcher threads), and every `Deps.forContext`
 * built its own `SessionStore` — with 12+ screens that meant 12+ independent
 * socket pools and 12+ session wrappers racing over the same file. One shared
 * client and one shared store per process keep sockets/threads flat and make the
 * token refresh one lock for the whole app (see `SessionStore.validAccessToken`).
 *
 * The client deliberately carries no session of its own: callers hand the
 * `SessionStore` in, so a test can still inject its own store and transport.
 */
object AppClients {

    /**
     * The one OkHttp client of the process. Thread-safe by contract (OkHttp
     * clients are designed to be shared), 15 s connect / 30 s read timeouts as
     * before — the pool and the dispatcher live for the whole process.
     */
    val defaultHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    @Volatile
    private var storeRef: SessionStore? = null

    /** The shared [SessionStore] (created once, bound to the application context). */
    fun sessionStore(context: Context): SessionStore =
        storeRef ?: synchronized(this) {
            storeRef ?: SessionStore(context.applicationContext).also { storeRef = it }
        }

    @Volatile
    private var clientRef: SupabaseClient? = null

    /**
     * The shared [SupabaseClient] — anon key plus the shared session's JWT, over
     * the shared transport. Screens that only read (dashboard, stats, history)
     * use this instead of building their own instance.
     */
    fun supabaseClient(context: Context): SupabaseClient =
        clientRef ?: synchronized(this) {
            clientRef ?: SupabaseClient(sessionStore(context)).also { clientRef = it }
        }
}

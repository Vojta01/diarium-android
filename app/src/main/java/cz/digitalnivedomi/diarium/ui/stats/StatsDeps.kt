package cz.digitalnivedomi.diarium.ui.stats

import android.content.Context
import cz.digitalnivedomi.diarium.auth.SessionStore
import cz.digitalnivedomi.diarium.core.data.EntriesRepository
import cz.digitalnivedomi.diarium.core.data.SessionContext
import cz.digitalnivedomi.diarium.core.data.StatsRepository
import cz.digitalnivedomi.diarium.core.data.SupabaseClient

/**
 * Wiring for the statistics tab, injected instead of built inline so the screen can
 * render without a session (previews, Robolectric) and so a test can hand it a
 * repository of its own.
 *
 * With a null [stats] the screen shows its retry state rather than numbers it cannot
 * verify — the same "no silent empty screen" rule the repository follows.
 *
 * The client carries only the anon key from [SupabaseClient]'s BuildConfig plus the
 * signed-in user's own JWT; the statistics read the user's rows under that JWT and
 * never need (or receive) a server-side key.
 */
class StatsDeps(val stats: StatsRepository? = null) {

    val online: Boolean get() = stats != null

    companion object {

        /** No session, no network — the screen still composes and shows its error state. */
        fun offline(): StatsDeps = StatsDeps()

        /** The real thing, built from the current context like `DashboardDeps.forContext`. */
        fun forContext(context: Context): StatsDeps {
            val sessionStore = SessionStore(context)
            val client = SupabaseClient(sessionStore)
            return StatsDeps(
                stats = StatsRepository(
                    EntriesRepository(client, SessionContext(sessionStore)),
                ),
            )
        }
    }
}

package cz.digitalnivedomi.diarium.ui.home

import android.content.Context
import cz.digitalnivedomi.diarium.auth.SessionStore
import cz.digitalnivedomi.diarium.core.data.DashboardRepository
import cz.digitalnivedomi.diarium.core.data.EntriesRepository
import cz.digitalnivedomi.diarium.core.data.SessionContext
import cz.digitalnivedomi.diarium.core.data.SupabaseClient

/**
 * Wiring for the dashboard, injected instead of built inline so the screen can
 * render without a session (previews, Robolectric) and so a test can hand it a
 * repository of its own.
 *
 * With a null [dashboard] the screen shows its retry state rather than numbers it
 * cannot verify — the same "no silent empty screen" rule the repository follows.
 *
 * The client carries only the anon key from [SupabaseClient]'s BuildConfig plus the
 * signed-in user's own JWT; the dashboard reads the user's rows under that JWT and
 * never needs (or receives) a server-side key.
 */
class DashboardDeps(val dashboard: DashboardRepository? = null) {

    val online: Boolean get() = dashboard != null

    companion object {

        /** No session, no network — the screen still composes and shows its error state. */
        fun offline(): DashboardDeps = DashboardDeps()

        /** The real thing, built from the current context like `CheckInDeps.forContext`. */
        fun forContext(context: Context): DashboardDeps {
            val sessionStore = SessionStore(context)
            val client = SupabaseClient(sessionStore)
            return DashboardDeps(
                dashboard = DashboardRepository(
                    EntriesRepository(client, SessionContext(sessionStore)),
                ),
            )
        }
    }
}

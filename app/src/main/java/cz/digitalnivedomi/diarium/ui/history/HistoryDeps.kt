package cz.digitalnivedomi.diarium.ui.history

import android.content.Context
import cz.digitalnivedomi.diarium.auth.SessionStore
import cz.digitalnivedomi.diarium.core.data.EntriesRepository
import cz.digitalnivedomi.diarium.core.data.HistoryRepository
import cz.digitalnivedomi.diarium.core.data.SessionContext
import cz.digitalnivedomi.diarium.core.data.SupabaseClient

/**
 * Wiring for the history screen, injected instead of built inline so the screen can
 * render without a session (previews, Robolectric) and so a test can hand it a
 * repository of its own.
 *
 * With a null [history] the screen shows its retry state rather than an empty
 * calendar it cannot verify — the same "no silent empty screen" rule the dashboard
 * follows.
 *
 * The client carries only the anon key from [SupabaseClient]'s BuildConfig plus the
 * signed-in user's own JWT; the history reads the user's rows under that JWT and
 * never needs (or receives) a server-side key.
 */
class HistoryDeps(val history: HistoryRepository? = null) {

    val online: Boolean get() = history != null

    companion object {

        /** No session, no network — the screen still composes and shows its error state. */
        fun offline(): HistoryDeps = HistoryDeps()

        /** The real thing, built from the current context like `DashboardDeps.forContext`. */
        fun forContext(context: Context): HistoryDeps {
            val sessionStore = SessionStore(context)
            val client = SupabaseClient(sessionStore)
            return HistoryDeps(
                history = HistoryRepository(
                    EntriesRepository(client, SessionContext(sessionStore)),
                ),
            )
        }
    }
}

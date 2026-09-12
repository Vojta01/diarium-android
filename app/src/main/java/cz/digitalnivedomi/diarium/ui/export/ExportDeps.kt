package cz.digitalnivedomi.diarium.ui.export

import android.content.Context
import cz.digitalnivedomi.diarium.auth.SessionStore
import cz.digitalnivedomi.diarium.core.data.ExportRepository
import cz.digitalnivedomi.diarium.core.data.SessionContext
import cz.digitalnivedomi.diarium.core.data.SupabaseClient

/**
 * Wiring for the export screen, injected instead of built inline so the screen can
 * render without a session (previews, Robolectric) and so a test can hand it a
 * repository of its own — the same shape as `GoalsDeps` / `HistoryDeps`.
 *
 * With a null [repository] the screen shows the "export is unavailable" state
 * rather than the "no entries" card: a screen that could not read must never claim
 * the diary is empty.
 *
 * The client carries the anon key from [SupabaseClient]'s BuildConfig plus the
 * signed-in user's own JWT. The export is client-side precisely so that the web
 * endpoint's `service_role` key never has to exist inside the APK.
 */
class ExportDeps(
    val repository: ExportRepository? = null,
) {

    val online: Boolean get() = repository != null

    companion object {

        /** No session, no network — the screen still composes and says so. */
        fun offline(): ExportDeps = ExportDeps()

        /** The real thing, built from the current context like `GoalsDeps.forContext`. */
        fun forContext(context: Context): ExportDeps {
            val sessionStore = SessionStore(context)
            val client = SupabaseClient(sessionStore)
            return ExportDeps(
                repository = ExportRepository(client, SessionContext(sessionStore)),
            )
        }
    }
}

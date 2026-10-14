package cz.digitalnivedomi.diarium.ui.reports

import android.content.Context
import cz.digitalnivedomi.diarium.auth.SessionStore
import cz.digitalnivedomi.diarium.core.data.AppClients
import cz.digitalnivedomi.diarium.core.data.AiReportsRepository
import cz.digitalnivedomi.diarium.core.data.HttpReportRequester
import cz.digitalnivedomi.diarium.core.data.ProfileStore
import cz.digitalnivedomi.diarium.core.data.SessionContext
import cz.digitalnivedomi.diarium.core.data.SupabaseClient

/**
 * Wiring for the AI reports screen, injected instead of built inline so the screen can
 * render without a session (previews, tests) and so a test can hand it a repository of
 * its own — the same shape as `ExportDeps` / `GoalsDeps`.
 *
 * With a null [repository] the screen shows the "reports are unavailable" state rather
 * than the "no report yet" card: a screen that could not read the table must never claim
 * the diary has nothing to summarise.
 *
 * The client carries the anon key from [SupabaseClient]'s BuildConfig plus the signed-in
 * user's own JWT, and RLS scopes the `ai_reports` rows to that account — the web's
 * `service_role` key never has to exist inside the APK. Generation itself is started by
 * [HttpReportRequester], which asks the same backend route the web button calls; the
 * report is then read straight from the table.
 *
 * The app-wide AI master switch lives in [profile]: with it off the screen shows the
 * AI-off banner and disables the generate buttons, so no tap here can reach the AI.
 */
class ReportsDeps(
    val repository: AiReportsRepository? = null,
    /**
     * The on-device profile — the AI master switch. Null only in offline/test wiring:
     * the screen then reads nothing and generating behaves exactly as it did before
     * the switch existed (the same shape as `CheckInDeps.profile`).
     */
    val profile: ProfileStore? = null,
) {

    val online: Boolean get() = repository != null

    companion object {

        /** No session, no network — the screen still composes and says so. */
        fun offline(): ReportsDeps = ReportsDeps()

        /** The real thing, built from the current context like `ExportDeps.forContext`. */
        fun forContext(context: Context): ReportsDeps {
            val sessionStore = AppClients.sessionStore(context)
            val client = AppClients.supabaseClient(context)
            val session = SessionContext(sessionStore)
            return ReportsDeps(
                repository = AiReportsRepository(
                    client = client,
                    session = session,
                    requester = HttpReportRequester(session),
                ),
                profile = ProfileStore.from(context),
            )
        }
    }
}

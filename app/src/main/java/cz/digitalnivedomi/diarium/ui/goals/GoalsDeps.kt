package cz.digitalnivedomi.diarium.ui.goals

import android.content.Context
import cz.digitalnivedomi.diarium.auth.SessionStore
import cz.digitalnivedomi.diarium.core.data.GoalsRepository
import cz.digitalnivedomi.diarium.core.data.SessionContext
import cz.digitalnivedomi.diarium.core.data.SupabaseClient

/**
 * Wiring for the goals screen, injected instead of built inline so the screen can
 * render without a session (previews, Robolectric) and so a test can hand it a
 * repository of its own — the same shape as `DashboardDeps`.
 *
 * With a null [goals] the screen shows its retry state rather than the "no goals"
 * card, because a screen that could not read must not claim the account is empty.
 *
 * The client carries only the anon key from [SupabaseClient]'s BuildConfig plus
 * the signed-in user's own JWT; every goals read/write goes through that JWT and
 * no server-side key is ever placed in the app.
 */
class GoalsDeps(
    val goals: GoalsRepository? = null,
) {

    val online: Boolean get() = goals != null

    companion object {

        /** No session, no network — the screen still composes and shows its error state. */
        fun offline(): GoalsDeps = GoalsDeps()

        /** The real thing, built from the current context like `DashboardDeps.forContext`. */
        fun forContext(context: Context): GoalsDeps {
            val sessionStore = SessionStore(context)
            val client = SupabaseClient(sessionStore)
            return GoalsDeps(
                goals = GoalsRepository(client, SessionContext(sessionStore)),
            )
        }
    }
}

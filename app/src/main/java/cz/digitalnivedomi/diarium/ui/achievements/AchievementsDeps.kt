package cz.digitalnivedomi.diarium.ui.achievements

import android.content.Context
import cz.digitalnivedomi.diarium.auth.SessionStore
import cz.digitalnivedomi.diarium.core.data.AchievementsRepository
import cz.digitalnivedomi.diarium.core.data.SessionContext
import cz.digitalnivedomi.diarium.core.data.SupabaseClient

/**
 * Wiring for the achievements panel, injected instead of built inline so the
 * screen can compose without a session (previews, Robolectric) and so a test can
 * hand it a repository of its own — mirroring [cz.digitalnivedomi.diarium.ui.home.DashboardDeps].
 *
 * With a null [achievements] the screen shows its retry state rather than an empty
 * grid that would read as "nothing earned"; the repository throws on a failed load
 * for the same reason.
 *
 * The client carries only the anon key from [SupabaseClient]'s BuildConfig plus the
 * signed-in user's own JWT ([SessionContext]); the panel never needs — and never
 * receives — a server-side key.
 */
class AchievementsDeps(
    val achievements: AchievementsRepository? = null,
) {

    val online: Boolean get() = achievements != null

    companion object {

        /** No session, no network — the screen still composes and shows its error state. */
        fun offline(): AchievementsDeps = AchievementsDeps()

        /** The real thing, built from the current context like `DashboardDeps.forContext`. */
        fun forContext(context: Context): AchievementsDeps {
            val sessionStore = SessionStore(context)
            val client = SupabaseClient(sessionStore)
            return AchievementsDeps(
                achievements = AchievementsRepository(client, SessionContext(sessionStore)),
            )
        }
    }
}

package cz.digitalnivedomi.diarium.ui.scales

import android.content.Context
import cz.digitalnivedomi.diarium.auth.SessionStore
import cz.digitalnivedomi.diarium.core.data.ScalesRepository
import cz.digitalnivedomi.diarium.core.data.SessionContext
import cz.digitalnivedomi.diarium.core.data.SupabaseClient

/**
 * Wiring for [ScalesScreen], injected instead of built inline so the screen can
 * compose without a session (previews, tests) and so a test can hand it a
 * repository of its own — the same shape as `DashboardDeps`.
 *
 * With a null [scales] the screen shows its retry state rather than an
 * "everything is fine, there are no scales" lie: signed out and genuinely empty
 * are different things and must not look the same.
 *
 * The client carries only the anon key from [SupabaseClient]'s BuildConfig plus
 * the signed-in user's own JWT; scale management reads and writes the user's rows
 * under that JWT and never needs (or receives) a server-side key.
 */
class ScalesDeps(
    val scales: ScalesRepository? = null,
) {

    val online: Boolean get() = scales != null

    companion object {

        /** No session, no network — the screen still composes and shows its error state. */
        fun offline(): ScalesDeps = ScalesDeps()

        /** The real thing, built from the current context like `DashboardDeps.forContext`. */
        fun forContext(context: Context): ScalesDeps {
            val sessionStore = SessionStore(context)
            return ScalesDeps(
                scales = ScalesRepository(
                    client = SupabaseClient(sessionStore),
                    session = SessionContext(sessionStore),
                ),
            )
        }
    }
}

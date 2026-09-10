package cz.digitalnivedomi.diarium.ui.checkin

import android.content.Context
import cz.digitalnivedomi.diarium.auth.SessionStore
import cz.digitalnivedomi.diarium.core.data.DraftStore
import cz.digitalnivedomi.diarium.core.data.EntriesRepository
import cz.digitalnivedomi.diarium.core.data.GoalsStore
import cz.digitalnivedomi.diarium.core.data.PickersRepository
import cz.digitalnivedomi.diarium.core.data.SessionContext
import cz.digitalnivedomi.diarium.core.data.SupabaseClient

/**
 * Wiring for the check-in screen, injected instead of constructed inline so the
 * screen can be rendered offline (previews, Robolectric) without a session or
 * network: with a null [entries] the form still edits, drafts and validates,
 * only the RPC calls are skipped.
 *
 * The client only ever carries the anon key from [SupabaseClient]'s BuildConfig
 * plus the signed-in user's own JWT from `SessionStore.validAccessToken()`.
 */
class CheckInDeps(
    val entries: EntriesRepository? = null,
    val pickers: PickersRepository? = null,
    val drafts: DraftStore? = null,
    val goals: GoalsStore? = null,
) {
    val online: Boolean get() = entries != null

    companion object {
        fun offline(): CheckInDeps = CheckInDeps()

        fun forContext(context: Context): CheckInDeps {
            val sessionStore = SessionStore(context)
            val client = SupabaseClient(sessionStore)
            val session = SessionContext(sessionStore)
            return CheckInDeps(
                entries = EntriesRepository(client, session),
                pickers = PickersRepository(client, session),
                drafts = DraftStore.from(context),
                goals = GoalsStore.from(context),
            )
        }
    }
}

package cz.digitalnivedomi.diarium.ui.checkin

import android.content.Context
import cz.digitalnivedomi.diarium.auth.SessionStore
import cz.digitalnivedomi.diarium.core.data.AiReflectionRepository
import cz.digitalnivedomi.diarium.core.data.DraftStore
import cz.digitalnivedomi.diarium.core.data.EntriesRepository
import cz.digitalnivedomi.diarium.core.data.GoalsRepository
import cz.digitalnivedomi.diarium.core.data.PickersRepository
import cz.digitalnivedomi.diarium.core.data.ProfileStore
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
    /**
     * Goal progress computed from the diary. The check-in only *shows* it — the
     * section is read-only (no manual ticking); goals are managed on the Cíle screen.
     */
    val goals: GoalsRepository? = null,
    /**
     * AI reflection for the signed-in user. Null while signed out: the section
     * then reports the connection failure instead of hiding, so the form looks
     * the same online and offline.
     */
    val reflection: AiReflectionRepository? = null,
    /**
     * The on-device profile: the AI master switch, plus the name and birth year the
     * reflection prompt may personalise with. Null only in offline/test wiring —
     * generation then behaves exactly as it did before the master switch existed.
     */
    val profile: ProfileStore? = null,
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
                goals = GoalsRepository(client, session),
                reflection = AiReflectionRepository(sessionStore),
                profile = ProfileStore.from(context),
            )
        }
    }
}

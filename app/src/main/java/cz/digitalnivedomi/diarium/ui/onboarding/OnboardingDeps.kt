package cz.digitalnivedomi.diarium.ui.onboarding

import android.content.Context
import cz.digitalnivedomi.diarium.auth.SessionStore
import cz.digitalnivedomi.diarium.core.data.AppClients
import cz.digitalnivedomi.diarium.core.data.AiKeyStore
import cz.digitalnivedomi.diarium.core.data.GoalsRepository
import cz.digitalnivedomi.diarium.core.data.PickersRepository
import cz.digitalnivedomi.diarium.core.data.ProfileStore
import cz.digitalnivedomi.diarium.core.data.SessionContext
import cz.digitalnivedomi.diarium.core.data.SupabaseClient

/**
 * Wiring for the first-run wizard, injected instead of built inline so the screen
 * can render without a session (previews, Robolectric) and so a test can hand it
 * fakes of its own — the same shape as `GoalsDeps` / `ProfileSettingsDeps`.
 *
 * The profile store and the AI key store are on-device (DataStore / encrypted
 * prefs); the pickers and goals repositories go through the anon key from
 * [SupabaseClient]'s BuildConfig plus the signed-in user's own JWT, exactly like
 * every other screen's dependencies.
 */
class OnboardingDeps(
    val profile: ProfileStore,
    val aiKeys: AiKeyStore,
    val pickers: PickersRepository,
    val goals: GoalsRepository,
    /** Opens an external URL in the browser; a no-op in previews and tests. */
    val openUrl: (String) -> Unit = {},
) {

    companion object {

        /** The real thing, built from the current context like `GoalsDeps.forContext`. */
        fun forContext(context: Context): OnboardingDeps {
            val sessionStore = AppClients.sessionStore(context)
            val client = AppClients.supabaseClient(context)
            val session = SessionContext(sessionStore)
            return OnboardingDeps(
                profile = ProfileStore.from(context),
                aiKeys = AiKeyStore.forContext(context),
                pickers = PickersRepository(client, session),
                goals = GoalsRepository(client, session),
                openUrl = { url ->
                    context.startActivity(
                        android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                },
            )
        }
    }
}

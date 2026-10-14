package cz.digitalnivedomi.diarium.ui.home

import android.content.Context
import cz.digitalnivedomi.diarium.auth.SessionStore
import cz.digitalnivedomi.diarium.core.data.AppClients
import cz.digitalnivedomi.diarium.core.data.AiReflectionRepository
import cz.digitalnivedomi.diarium.core.data.DashboardRepository
import cz.digitalnivedomi.diarium.core.data.EntriesCacheStore
import cz.digitalnivedomi.diarium.core.data.EntriesRepository
import cz.digitalnivedomi.diarium.core.data.GoalsRepository
import cz.digitalnivedomi.diarium.core.data.PendingSaveStore
import cz.digitalnivedomi.diarium.core.data.ProfileStore
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
class DashboardDeps(
    val dashboard: DashboardRepository? = null,
    /**
     * AI reflection for the signed-in user, used by the dashboard's "generate"
     * button. Null while signed out: the button then reports the connection
     * failure instead of hiding, so the card looks the same online and offline.
     * Nothing here is touched on load — the endpoint is called only on a tap.
     */
    val reflection: AiReflectionRepository? = null,
    /**
     * Goals for the dashboard card. Null while signed out: the card hides rather
     * than claiming "no goals" for a state it cannot read.
     */
    val goals: GoalsRepository? = null,
    /**
     * The on-device profile — the AI master switch ([ProfileData.aiEnabled]) the
     * reflection card respects, plus the name/birth year sent with a generation.
     */
    val profile: ProfileStore? = null,
) {

    val online: Boolean get() = dashboard != null

    companion object {

        /** No session, no network — the screen still composes and shows its error state. */
        fun offline(): DashboardDeps = DashboardDeps()

        /** The real thing, built from the current context like `CheckInDeps.forContext`. */
        fun forContext(context: Context): DashboardDeps {
            val sessionStore = AppClients.sessionStore(context)
            val client = AppClients.supabaseClient(context)
            return DashboardDeps(
                dashboard = DashboardRepository(
                    EntriesRepository(
                        client,
                        SessionContext(sessionStore),
                        // Snapshot for the instant cold-start paint, outbox for
                        // offline saves — see DashboardScreen's load effect.
                        cache = EntriesCacheStore.from(context),
                        pending = PendingSaveStore.from(context),
                    ),
                ),
                reflection = AiReflectionRepository(sessionStore),
                goals = GoalsRepository(client, SessionContext(sessionStore)),
                profile = ProfileStore.from(context),
            )
        }
    }
}

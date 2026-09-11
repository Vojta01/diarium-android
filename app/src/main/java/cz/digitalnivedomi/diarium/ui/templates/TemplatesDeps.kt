package cz.digitalnivedomi.diarium.ui.templates

import android.content.Context
import cz.digitalnivedomi.diarium.auth.SessionStore
import cz.digitalnivedomi.diarium.core.data.SessionContext
import cz.digitalnivedomi.diarium.core.data.SupabaseClient
import cz.digitalnivedomi.diarium.core.data.TemplatesRepository

/**
 * Wiring for the templates screen, injected instead of built inline so the
 * screen can render without a session (previews, Robolectric) and so a test can
 * hand it a repository of its own.
 *
 * With a null [templates] the screen shows its retry state instead of an empty
 * list it cannot verify — an offline app must not look like "you have no
 * templates".
 *
 * The client carries only the anon key from [SupabaseClient]'s BuildConfig plus
 * the signed-in user's own JWT; the repository reads and writes the user's rows
 * under that JWT and never needs (or receives) a server-side key.
 */
class TemplatesDeps(
    val templates: TemplatesRepository? = null,
) {

    val online: Boolean get() = templates != null

    companion object {

        /** No session, no network — the screen still composes and shows its error state. */
        fun offline(): TemplatesDeps = TemplatesDeps()

        /** The real thing, built from the current context like `DashboardDeps.forContext`. */
        fun forContext(context: Context): TemplatesDeps {
            val sessionStore = SessionStore(context)
            val client = SupabaseClient(sessionStore)
            return TemplatesDeps(
                templates = TemplatesRepository(client, SessionContext(sessionStore)),
            )
        }
    }
}

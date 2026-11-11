package cz.digitalnivedomi.diarium.ui.settings

import android.content.Context
import cz.digitalnivedomi.diarium.core.data.AppClients
import cz.digitalnivedomi.diarium.core.data.LegacyRecoveryCoordinator
import cz.digitalnivedomi.diarium.core.data.LegacyRecoveryScanner
import cz.digitalnivedomi.diarium.core.data.PreferenceLegacyRecoveryWriter

/** Local-only recovery wiring; deliberately does not construct an entries/network repository. */
class LegacyRecoveryDeps(val coordinator: LegacyRecoveryCoordinator) {
    companion object {
        fun forContext(context: Context): LegacyRecoveryDeps {
            val appContext = context.applicationContext
            val session = AppClients.sessionStore(appContext)
            val scanner = LegacyRecoveryScanner.from(appContext)
            return LegacyRecoveryDeps(
                LegacyRecoveryCoordinator(
                    scanner = scanner,
                    writer = PreferenceLegacyRecoveryWriter(appContext),
                    verifiedOwnerId = session::verifiedUserSubject,
                ),
            )
        }
    }
}

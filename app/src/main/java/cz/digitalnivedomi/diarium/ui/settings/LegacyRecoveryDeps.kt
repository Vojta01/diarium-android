package cz.digitalnivedomi.diarium.ui.settings

import android.content.Context
import cz.digitalnivedomi.diarium.core.data.AiKeyStore
import cz.digitalnivedomi.diarium.core.data.AppClients
import cz.digitalnivedomi.diarium.core.data.LegacyDataAdoption
import cz.digitalnivedomi.diarium.core.data.LegacyRecoveryCoordinator
import cz.digitalnivedomi.diarium.core.data.LegacyRecoveryScanner
import cz.digitalnivedomi.diarium.core.data.PreferenceLegacyRecoveryWriter
import cz.digitalnivedomi.diarium.core.data.ProfileStore

/** Local-only recovery wiring; deliberately does not construct an entries/network repository. */
class LegacyRecoveryDeps(
    val coordinator: LegacyRecoveryCoordinator,
    val adoption: LegacyDataAdoption,
) {
    companion object {
        fun forContext(context: Context): LegacyRecoveryDeps {
            val appContext = context.applicationContext
            val session = AppClients.sessionStore(appContext)
            val scanner = LegacyRecoveryScanner.from(appContext)
            return LegacyRecoveryDeps(
                coordinator = LegacyRecoveryCoordinator(
                    scanner = scanner,
                    writer = PreferenceLegacyRecoveryWriter(appContext),
                    verifiedOwnerId = session::verifiedUserSubject,
                ),
                adoption = LegacyDataAdoption(
                    profileFor = { owner -> ProfileStore.from(appContext, owner) },
                    keyFor = { owner -> AiKeyStore.forContext(appContext, owner) },
                    verifiedOwnerId = session::verifiedUserSubject,
                ),
            )
        }
    }
}

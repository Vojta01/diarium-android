package cz.digitalnivedomi.diarium.ui.settings

import cz.digitalnivedomi.diarium.ui.i18n.tr
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import cz.digitalnivedomi.diarium.ui.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import cz.digitalnivedomi.diarium.core.data.AiKeyStore
import cz.digitalnivedomi.diarium.core.data.AppClients
import cz.digitalnivedomi.diarium.core.data.LegacySecretPresence
import cz.digitalnivedomi.diarium.core.data.ProfileStore
import cz.digitalnivedomi.diarium.core.data.SessionContext
import cz.digitalnivedomi.diarium.ui.checkin.components.GlassTextField
import cz.digitalnivedomi.diarium.ui.checkin.components.TogglePill
import cz.digitalnivedomi.diarium.ui.components.GlassCard
import cz.digitalnivedomi.diarium.ui.components.GlassDivider
import cz.digitalnivedomi.diarium.ui.components.SubScreenEntry
import cz.digitalnivedomi.diarium.ui.components.VSpace
import cz.digitalnivedomi.diarium.ui.components.rememberHaptics
import cz.digitalnivedomi.diarium.ui.theme.Indigo
import cz.digitalnivedomi.diarium.ui.theme.SuccessGreen
import cz.digitalnivedomi.diarium.ui.theme.TextPrimary
import cz.digitalnivedomi.diarium.ui.theme.TextSecondary
import cz.digitalnivedomi.diarium.ui.theme.TextTertiary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The "AI (DeepSeek)" card in Nastavení — the bring-your-own-key surface of
 * option C (owner decision 2026-10-11).
 *
 * What it does: lets the user paste their own DeepSeek API key, save it into
 * the encrypted [AiKeyStore], remove it again, and jump to
 * `platform.deepseek.com` to mint one. The key round-trips here and nowhere
 * else — generation with it happens in the background ([
 * cz.digitalnivedomi.diarium.core.data.ReportGenerator]); the server route stays
 * only as the fallback for accounts without a key.
 */
class AiSettingsDeps(
    val keyStatus: () -> LegacySecretPresence,
    val saveKey: (String) -> Boolean,
    val removeKey: () -> Unit,
    val openKeyPage: () -> Unit,
    /** Presence-only legacy-key state; UNKNOWN must not be rendered as absent. */
    val legacyValueStatus: () -> LegacySecretPresence = { LegacySecretPresence.UNKNOWN },
    /** The on-device profile — the AI master switch lives there. */
    val profile: ProfileStore,
) {

    companion object {

        /** No DataStore, no browser — the card still composes (previews, unit tests). */
        fun offline(): AiSettingsDeps = AiSettingsDeps(
            keyStatus = { LegacySecretPresence.ABSENT },
            saveKey = { false },
            removeKey = {},
            openKeyPage = {},
            legacyValueStatus = { LegacySecretPresence.ABSENT },
            profile = ProfileStore.forDataStore(TransientPreferencesDataStore(), "offline"),
        )

        /** The real thing: the encrypted store + an ACTION_VIEW browser intent. */
        fun forContext(context: Context): AiSettingsDeps {
            val session = SessionContext(AppClients.sessionStore(context))
            val store = AiKeyStore.forContext(context, session.userId())
            return AiSettingsDeps(
                keyStatus = { store.keyStatus() },
                saveKey = { raw -> store.saveKey(raw) },
                removeKey = { store.clear() },
                legacyValueStatus = { store.legacyValueStatus() },
                openKeyPage = { openKeyPage(context) },
                profile = ProfileStore.from(context, session.userId()),
            )
        }

        private fun openKeyPage(context: Context) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(KEY_PAGE_URL))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
            } catch (_: ActivityNotFoundException) {
                // No browser on the device — the link is printed above the row anyway.
            }
        }

        /** Where a new API key is minted; opened in the browser, never in-app. */
        const val KEY_PAGE_URL = "https://platform.deepseek.com/api_keys"
    }
}

/**
 * In-memory [DataStore] behind [AiSettingsDeps.offline]: a preview or a unit test gets a
 * working AI master switch without a Context, a DataStore file or the Keystore. The app
 * itself always uses the real store ([ProfileStore.from]).
 */
private class TransientPreferencesDataStore : DataStore<Preferences> {

    private val state = MutableStateFlow<Preferences>(emptyPreferences())

    override val data: Flow<Preferences> = state.asStateFlow()

    override suspend fun updateData(
        transform: suspend (t: Preferences) -> Preferences,
    ): Preferences {
        val updated = transform(state.value)
        state.value = updated
        return updated
    }
}

/**
 * The card itself. The key status is read off the main thread (the first
 * EncryptedSharedPreferences read does the Keystore handshake) and every write
 * goes through the same IO dispatcher, so the settings scroll never janks.
 */
@Composable
fun AiSettingsSection(deps: AiSettingsDeps, modifier: Modifier = Modifier) {
    var keyStatus by remember { mutableStateOf(LegacySecretPresence.UNKNOWN) }
    var legacyKeyStatus by remember { mutableStateOf(LegacySecretPresence.UNKNOWN) }
    var aiEnabled by remember { mutableStateOf(false) }
    var field by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()

    LaunchedEffect(Unit) {
        keyStatus = withContext(Dispatchers.IO) { deps.keyStatus() }
        legacyKeyStatus = withContext(Dispatchers.IO) { deps.legacyValueStatus() }
        // The master switch lives in the profile store; read once, like the key state.
        aiEnabled = withContext(Dispatchers.IO) { deps.profile.load().aiEnabled }
    }

    GlassCard(modifier = modifier.fillMaxWidth()) {
        // ── AI master switch: the first thing in the card, because it rules the rest ──
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "AI zapnutá",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                modifier = Modifier.weight(1f),
            )
            TogglePill(
                checked = aiEnabled,
                testTag = "ai_master_toggle",
                onClick = {
                    val next = !aiEnabled
                    aiEnabled = next
                    // The write is what makes the switch survive a restart; the pill
                    // itself answers the tap immediately.
                    scope.launch {
                        withContext(Dispatchers.IO) { deps.profile.setAiEnabled(next) }
                    }
                },
            )
        }
        VSpace(4)
        Text(
            text = "Vypnutá AI = žádné reflexe ani přehledy se negenerují. Klíč zůstává uložený v telefonu.",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
        )
        VSpace(10)
        GlassDivider()
        VSpace(2)

        Text(
            text = "AI (DeepSeek)",
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
        )
        VSpace(4)
        Text(
            text = "Týdenní a měsíční reflexe. S vlastním klíčem je generuje přímo telefon.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
        )
        VSpace(12)

        // State line: saved, absent, or not safely verifiable.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (keyStatus == LegacySecretPresence.PRESENT) SuccessGreen else TextTertiary),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = when (keyStatus) {
                    LegacySecretPresence.PRESENT -> "Klíč uložen"
                    LegacySecretPresence.ABSENT -> "Žádný klíč"
                    LegacySecretPresence.UNKNOWN -> "Stav klíče nelze ověřit"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (keyStatus == LegacySecretPresence.PRESENT) SuccessGreen else TextSecondary,
                modifier = Modifier.testTag("ai_key_state"),
            )
        }
        if (keyStatus == LegacySecretPresence.UNKNOWN) {
            VSpace(8)
            Text(
                text = "Stav uloženého klíče se nepodařilo ověřit. Nezadávej ho znovu, dokud nebude možné úložiště bezpečně přečíst.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                modifier = Modifier.testTag("ai_key_status_unknown"),
            )
        }
        when (legacyKeyStatus) {
            LegacySecretPresence.PRESENT -> {
                VSpace(8)
                Text(
                    text = "Starší klíč bez přiřazeného účtu zůstal v telefonu nezměněný, ale aplikace ho nepoužije ani nezobrazí. Zadej znovu klíč, jehož vlastnictví můžeš ověřit; před smazáním dat aplikace zachovej toto zařízení.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    modifier = Modifier.testTag("ai_legacy_key_quarantined"),
                )
            }
            LegacySecretPresence.UNKNOWN -> {
                VSpace(8)
                Text(
                    text = "Nepodařilo se bezpečně ověřit, zda je uložený starší klíč. Data nebyla změněna a neověřený klíč se nepoužije.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    modifier = Modifier.testTag("ai_legacy_key_status_unknown"),
                )
            }
            LegacySecretPresence.ABSENT -> Unit
        }
        if (keyStatus == LegacySecretPresence.ABSENT) {
            VSpace(4)
            Text(
                text = "Přidej si AI klíč — přehledy se pak generují přímo v telefonu.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
        }
        VSpace(12)

        GlassTextField(
            value = field,
            onValueChange = { field = it },
            placeholder = tr("Vlož svůj DeepSeek API klíč"),
            testTag = "ai_key_field",
            visualTransformation = PasswordVisualTransformation(),
        )
        VSpace(10)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = {
                    val value = field
                    scope.launch {
                        val saved = withContext(Dispatchers.IO) { deps.saveKey(value) }
                        if (saved) {
                            field = ""
                            keyStatus = LegacySecretPresence.PRESENT
                            haptics.success()
                        }
                    }
                },
                enabled = field.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Indigo),
                modifier = Modifier.testTag("ai_key_save"),
            ) {
                Text("Uložit")
            }
            Spacer(Modifier.width(10.dp))
            OutlinedButton(
                onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { deps.removeKey() }
                        keyStatus = LegacySecretPresence.ABSENT
                        haptics.light()
                    }
                },
                enabled = keyStatus == LegacySecretPresence.PRESENT,
                modifier = Modifier.testTag("ai_key_remove"),
            ) {
                Text("Odebrat")
            }
        }

        VSpace(6)
        GlassDivider()
        SubScreenEntry(
            emoji = "🔑",
            title = "Získat klíč na platform.deepseek.com",
            hint = "Otevře se v prohlížeči",
            onClick = deps.openKeyPage,
        )
        VSpace(2)
        Text(
            text = "Klíč se ukládá šifrovaně jen v tomto telefonu. Na naše servery se nikdy neposílá.",
            style = MaterialTheme.typography.bodySmall,
            color = TextTertiary,
        )
    }
}

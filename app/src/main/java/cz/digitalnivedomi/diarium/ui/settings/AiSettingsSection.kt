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
import cz.digitalnivedomi.diarium.core.data.ProfileStore
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
    val hasKey: () -> Boolean,
    val saveKey: (String) -> Boolean,
    val removeKey: () -> Unit,
    val openKeyPage: () -> Unit,
    /** The on-device profile — the AI master switch lives there. */
    val profile: ProfileStore,
) {

    companion object {

        /** No DataStore, no browser — the card still composes (previews, unit tests). */
        fun offline(): AiSettingsDeps = AiSettingsDeps(
            hasKey = { false },
            saveKey = { false },
            removeKey = {},
            openKeyPage = {},
            profile = ProfileStore.forDataStore(TransientPreferencesDataStore()),
        )

        /** The real thing: the encrypted store + an ACTION_VIEW browser intent. */
        fun forContext(context: Context): AiSettingsDeps {
            val store = AiKeyStore.forContext(context)
            return AiSettingsDeps(
                hasKey = { store.hasKey() },
                saveKey = { raw -> store.saveKey(raw) },
                removeKey = { store.clear() },
                openKeyPage = { openKeyPage(context) },
                profile = ProfileStore.from(context),
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
    var hasKey by remember { mutableStateOf<Boolean?>(null) }
    var aiEnabled by remember { mutableStateOf(true) }
    var field by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()

    LaunchedEffect(Unit) {
        hasKey = withContext(Dispatchers.IO) { deps.hasKey() }
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

        // State line: "Klíč uložen" / "Žádný klíč" (plus the nudge).
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (hasKey == true) SuccessGreen else TextTertiary),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (hasKey == true) "Klíč uložen" else "Žádný klíč",
                style = MaterialTheme.typography.bodyMedium,
                color = if (hasKey == true) SuccessGreen else TextSecondary,
                modifier = Modifier.testTag("ai_key_state"),
            )
        }
        if (hasKey == false) {
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
                            hasKey = true
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
                        hasKey = false
                        haptics.light()
                    }
                },
                enabled = hasKey == true,
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

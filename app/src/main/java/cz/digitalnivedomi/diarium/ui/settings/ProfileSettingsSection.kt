package cz.digitalnivedomi.diarium.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cz.digitalnivedomi.diarium.core.data.ProfileStore
import cz.digitalnivedomi.diarium.ui.checkin.components.GlassTextField
import cz.digitalnivedomi.diarium.ui.components.GlassCard
import cz.digitalnivedomi.diarium.ui.components.GlassDivider
import cz.digitalnivedomi.diarium.ui.components.VSpace
import cz.digitalnivedomi.diarium.ui.components.rememberHaptics
import cz.digitalnivedomi.diarium.ui.theme.ErrorRed
import cz.digitalnivedomi.diarium.ui.theme.Indigo
import cz.digitalnivedomi.diarium.ui.theme.SuccessGreen
import cz.digitalnivedomi.diarium.ui.theme.TextPrimary
import cz.digitalnivedomi.diarium.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Wiring for the "👤 Profil" card: the on-device profile store, and the one action the
 * card performs beyond saving — putting the owner back through onboarding.
 */
class ProfileSettingsDeps(val store: ProfileStore) {

    companion object {
        /** The real thing, built from the current context like `AiSettingsDeps.forContext`. */
        fun forContext(context: Context): ProfileSettingsDeps =
            ProfileSettingsDeps(ProfileStore.from(context))
    }

    /** "Spustit průvodce znovu": the next launch walks through the guide again. */
    suspend fun restartOnboarding() = store.setOnboardingDone(false)
}

/** The birth years the field accepts. Outside this range nothing is saved. */
private val BIRTH_YEAR_RANGE = 1900..2100

/**
 * The "👤 Profil" card in Nastavení.
 *
 * What it does: lets the owner store the name and birth year the AI uses to make its
 * reflections and reports personal, and to restart the first-run guide. Both values
 * stay on this phone (the [ProfileStore] file) — the helper text says so, because the
 * name is the one piece of the card the owner might not expect a local AI to know.
 */
@Composable
fun ProfileSettingsSection(deps: ProfileSettingsDeps, modifier: Modifier = Modifier) {
    var name by remember { mutableStateOf("") }
    var birthYear by remember { mutableStateOf("") }
    var birthYearError by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()

    // The stored profile is read off the main thread (the first DataStore read hits
    // the disk), then copied into the two fields — the same shape as the AI key state.
    LaunchedEffect(deps) {
        val profile = withContext(Dispatchers.IO) { deps.store.load() }
        name = profile.name
        birthYear = profile.birthYear?.toString().orEmpty()
    }

    // "Uloženo ✓" is a confirmation, not a state — it fades after a moment.
    LaunchedEffect(saved) {
        if (saved) {
            delay(2500)
            saved = false
        }
    }

    /**
     * The write: the name as typed (the store trims it) and the year, or null when the
     * field is empty — an invalid year keeps the values on screen and shows the error
     * instead of silently storing something else.
     */
    fun save() {
        val rawYear = birthYear.trim()
        val year = rawYear.toIntOrNull()
        if (rawYear.isNotEmpty() && (year == null || year !in BIRTH_YEAR_RANGE)) {
            birthYearError = true
            haptics.light()
            return
        }
        birthYearError = false
        saving = true
        saved = false
        scope.launch {
            withContext(Dispatchers.IO) {
                deps.store.saveName(name)
                deps.store.saveBirthYear(year)
            }
            saving = false
            saved = true
            haptics.success()
        }
    }

    GlassCard(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "👤 Profil",
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
        )
        VSpace(4)
        Text(
            text = "Jméno a rok narození používá AI pro osobnější reflexe a přehledy. Zůstávají jen v tomto telefonu.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
        )
        VSpace(12)

        GlassTextField(
            value = name,
            onValueChange = {
                name = it
                saved = false
            },
            placeholder = "Jméno",
            testTag = "profile_name",
        )
        VSpace(10)
        GlassTextField(
            value = birthYear,
            onValueChange = { raw ->
                // The numeric keyboard already refuses letters; the filter makes a
                // pasted "1990 " safe too, and four digits is all a year needs.
                birthYear = raw.filter { it.isDigit() }.take(4)
                birthYearError = false
                saved = false
            },
            placeholder = "Rok narození",
            testTag = "profile_birth_year",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        if (birthYearError) {
            VSpace(6)
            Text(
                text = "Zadej rok mezi 1900 a 2100.",
                style = MaterialTheme.typography.bodySmall,
                color = ErrorRed,
                modifier = Modifier.testTag("profile_birth_year_error"),
            )
        }
        VSpace(12)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = { save() },
                enabled = !saving,
                colors = ButtonDefaults.buttonColors(containerColor = Indigo),
                modifier = Modifier.testTag("profile_save"),
            ) {
                Text(if (saving) "⏳ Ukládám…" else "Uložit")
            }
            if (saved) {
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "Uloženo ✓",
                    style = MaterialTheme.typography.bodySmall,
                    color = SuccessGreen,
                    modifier = Modifier.testTag("profile_saved"),
                )
            }
        }

        VSpace(6)
        GlassDivider()
        OutlinedButton(
            onClick = {
                scope.launch {
                    withContext(Dispatchers.IO) { deps.restartOnboarding() }
                    haptics.light()
                }
            },
            modifier = Modifier.testTag("profile_onboarding_restart"),
        ) {
            Text("🧭 Spustit průvodce znovu")
        }
    }
}

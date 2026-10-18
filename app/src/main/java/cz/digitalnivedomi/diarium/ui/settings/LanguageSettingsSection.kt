package cz.digitalnivedomi.diarium.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cz.digitalnivedomi.diarium.ui.components.GlassCard
import cz.digitalnivedomi.diarium.ui.components.VSpace
import cz.digitalnivedomi.diarium.ui.components.rememberHaptics
import cz.digitalnivedomi.diarium.core.i18n.AppLanguage
import cz.digitalnivedomi.diarium.core.i18n.Lang
import cz.digitalnivedomi.diarium.ui.i18n.Text
import cz.digitalnivedomi.diarium.ui.theme.Indigo
import cz.digitalnivedomi.diarium.ui.theme.IndigoLight
import cz.digitalnivedomi.diarium.ui.theme.TextPrimary
import cz.digitalnivedomi.diarium.ui.theme.TextSecondary

/**
 * The "🌐 Jazyk / Language" card in Nastavení.
 *
 * The switch is instant: [AppLanguage.current] is Compose state, so the whole tree recomposes
 * and every `Text` wrapper re-translates. No restart, no Activity recreation.
 *
 * The card deliberately says the setting applies to the interface only. Journal entries, notes,
 * goals and AI output are user content and keep the language they were written in.
 */
@Composable
fun LanguageSettingsSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val selected = AppLanguage.current
    val haptics = rememberHaptics()

    GlassCard(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "🌐 Jazyk / Language",
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
        )
        VSpace(4)
        Text(
            text = "Přepne jazyk rozhraní. Zápisy a poznámky zůstanou v jazyce, ve kterém jsi je napsal.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
        )
        VSpace(4)
        Text(
            text = "AI reflexe a přehledy zatím zůstávají česky.",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
        )
        VSpace(12)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LanguageOption(
                label = "Čeština",
                selected = selected == Lang.CS,
                testTag = "language_cs",
            ) {
                if (selected != Lang.CS) haptics.success()
                AppLanguage.set(context, Lang.CS)
            }
            LanguageOption(
                label = "English",
                selected = selected == Lang.EN,
                testTag = "language_en",
            ) {
                if (selected != Lang.EN) haptics.success()
                AppLanguage.set(context, Lang.EN)
            }
        }
        if (selected == Lang.EN) {
            VSpace(10)
            Text(
                text = "Angličtina je zatím strojový překlad. Když něco nesedí, přepni zpět na češtinu.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
        }
    }
}

@Composable
private fun LanguageOption(
    label: String,
    selected: Boolean,
    testTag: String,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(if (selected) Indigo.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.04f))
            .border(1.dp, if (selected) IndigoLight else Color.White.copy(alpha = 0.12f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp)
            .testTag(testTag),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) TextPrimary else TextSecondary,
        )
    }
}

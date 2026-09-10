package cz.digitalnivedomi.diarium.ui.checkin.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import cz.digitalnivedomi.diarium.ui.theme.Indigo
import cz.digitalnivedomi.diarium.ui.theme.TextPrimary
import cz.digitalnivedomi.diarium.ui.theme.TextTertiary

/**
 * 🤖 AI reflexe — the day's generated reflection, the last section of the form.
 *
 * The text is the server's own Czech prose, so it is rendered verbatim. Four
 * states share this one section: nothing yet (an offer to write one), generating
 * (the action is disabled and shows a spinner, so the request cannot be fired
 * twice), done (the text, a reminder that it is stored with the day, and a way to
 * regenerate — the endpoint caches, so a second look is cheap) and failed (the
 * Czech sentence the repository returned).
 */
@Composable
fun ReflectionSection(
    reflection: String?,
    loading: Boolean,
    error: String?,
    onGenerate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasReflection = !reflection.isNullOrBlank()
    val spinner: (@Composable () -> Unit)? = if (loading) {
        {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = TextTertiary,
            )
        }
    } else {
        null
    }

    CheckInSection(title = "🤖 AI reflexe", modifier = modifier) {
        if (hasReflection) {
            val shape = RoundedCornerShape(14.dp)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(Indigo.copy(alpha = 0.12f))
                    .border(1.dp, Indigo.copy(alpha = 0.45f), shape)
                    .padding(14.dp),
            ) {
                Text(
                    text = reflection.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Reflexe je uložená u dnešního zápisu.",
                style = MaterialTheme.typography.labelSmall,
                color = TextTertiary,
            )
            Spacer(Modifier.height(10.dp))
        } else {
            SectionHint("Nech AI, ať se podívá na tvůj den a napíše krátkou reflexi.")
            Spacer(Modifier.height(10.dp))
        }

        error?.let { message ->
            ErrorBanner(message)
            Spacer(Modifier.height(10.dp))
        }

        if (hasReflection) {
            if (loading) {
                // Regenerating keeps the old text visible — it is replaced only
                // when the new one arrives.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    spinner?.invoke()
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Píšu novou reflexi…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextTertiary,
                    )
                }
            } else {
                SecondaryButton(text = "Znovu vygenerovat", testTag = "reflection_regenerate") {
                    onGenerate()
                }
            }
        } else {
            PrimaryButton(
                text = if (loading) "Píšu reflexi…" else "Napsat reflexi",
                enabled = !loading,
                testTag = "reflection_generate",
                leading = spinner,
            ) {
                onGenerate()
            }
        }
    }
}

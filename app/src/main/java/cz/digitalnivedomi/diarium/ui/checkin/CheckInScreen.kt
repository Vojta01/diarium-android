package cz.digitalnivedomi.diarium.ui.checkin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cz.digitalnivedomi.diarium.ui.components.GlassCard
import cz.digitalnivedomi.diarium.ui.components.GlassChip
import cz.digitalnivedomi.diarium.ui.theme.TextSecondary

/**
 * Daily check-in. M0 ships the shell so the navigation and design system are
 * verifiable end to end; the real form lands in M2.
 */
@Composable
fun CheckInScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Dnešní zápis",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            GlassChip(text = "M2")
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Nálada, spánek, aktivity a poznámky. Formulář se staví v tomto kroku.",
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondary,
            )
        }
    }
}

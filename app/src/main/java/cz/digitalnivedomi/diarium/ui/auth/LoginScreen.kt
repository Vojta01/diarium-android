package cz.digitalnivedomi.diarium.ui.auth

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cz.digitalnivedomi.diarium.ui.components.GlassCard
import cz.digitalnivedomi.diarium.ui.components.VSpace
import cz.digitalnivedomi.diarium.ui.theme.ErrorRed
import cz.digitalnivedomi.diarium.ui.theme.Indigo
import cz.digitalnivedomi.diarium.ui.theme.IndigoLight
import cz.digitalnivedomi.diarium.ui.theme.Ink
import cz.digitalnivedomi.diarium.ui.theme.TextPrimary
import cz.digitalnivedomi.diarium.ui.theme.TextSecondary
import cz.digitalnivedomi.diarium.ui.theme.TextTertiary
import cz.digitalnivedomi.diarium.ui.theme.Violet

/** Test tag so the button can be asserted on in Robolectric tests. */
const val GoogleSignInButtonTag = "google-sign-in"

/** Czech copy, kept next to the screen that renders it (same convention as the other screens). */
const val GoogleSignInLabel = "Pokračovat přes Google"
const val GoogleSignInBusyLabel = "Otevírám přihlášení…"

/**
 * Real sign-in screen: opens the Supabase Google OAuth flow in a Chrome Custom
 * Tab (`onSignIn` → `AuthManager.startSignIn`). The deep link that comes back is
 * handled by the app shell — this screen only reflects [isSigningIn].
 *
 * Visual language is the shared one: ink background (drawn by the shell), indigo
 * accents, glass card, no flat Material grey.
 */
@Composable
fun LoginScreen(
    isSigningIn: Boolean = false,
    onSignIn: () -> Unit = {},
    errorMessage: String? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BrandMark()

        Spacer(Modifier.height(20.dp))
        Text(
            text = "Diarium",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Deník nálady, spánku a času u obrazovky. Zapsaný za 30 sekund.",
            style = MaterialTheme.typography.bodyLarge,
            color = TextSecondary,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(24.dp))
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            FeatureLine("Nálada, spánek, aktivity a poznámky")
            VSpace(12)
            FeatureLine("Kalendář, série a přehledy")
            VSpace(12)
            FeatureLine("Připomínky a AI reflexe")
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onSignIn,
            enabled = !isSigningIn,
            shape = MaterialTheme.shapes.medium,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.White,
                contentColor = Ink,
                disabledContainerColor = Color.White.copy(alpha = 0.55f),
                disabledContentColor = TextTertiary,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .testTag(GoogleSignInButtonTag),
        ) {
            if (isSigningIn) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = Indigo,
                )
            } else {
                GoogleGlyph(modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = if (isSigningIn) GoogleSignInBusyLabel else GoogleSignInLabel,
                style = MaterialTheme.typography.titleMedium,
            )
        }

        if (isSigningIn) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Dokončení probíhá v prohlížeči Google.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center,
            )
        }

        if (errorMessage != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = ErrorRed,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(Modifier.height(20.dp))
        Text(
            text = "Přihlášením se deník ukládá do tvého účtu Google. Žádná data neposíláme nikam jinam.",
            style = MaterialTheme.typography.labelSmall,
            color = TextTertiary,
            textAlign = TextAlign.Center,
        )
    }
}

/** Brand mark: indigo→violet glass square with a "D". */
@Composable
private fun BrandMark() {
    Box(
        modifier = Modifier
            .size(76.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(Indigo, Violet)))
            .border(1.dp, Color.White.copy(alpha = 0.20f), RoundedCornerShape(24.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "D",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
    }
}

/** One value-proposition line with a small indigo check dot. */
@Composable
private fun FeatureLine(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(Indigo.copy(alpha = 0.22f))
                .border(1.dp, Indigo.copy(alpha = 0.50f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "✓",
                style = MaterialTheme.typography.labelSmall,
                color = IndigoLight,
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = TextPrimary,
        )
    }
}

/**
 * The Google "G", drawn with four arcs plus the crossbar — no new dependency and
 * nothing to keep in sync with an asset.
 */
@Composable
private fun GoogleGlyph(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val stroke = size.minDimension * 0.22f
        val diameter = size.minDimension - stroke
        val topLeft = Offset(stroke / 2f, stroke / 2f)
        val arcSize = Size(diameter, diameter)
        val style = Stroke(width = stroke, cap = StrokeCap.Butt)

        // Blue right, green bottom, yellow left, red top — small gaps between them.
        drawArc(Color(0xFF4285F4), -25f, 95f, false, topLeft, arcSize, style = style)
        drawArc(Color(0xFF34A853), 80f, 80f, false, topLeft, arcSize, style = style)
        drawArc(Color(0xFFFBBC05), 175f, 80f, false, topLeft, arcSize, style = style)
        drawArc(Color(0xFFEA4335), 268f, 60f, false, topLeft, arcSize, style = style)

        // Crossbar of the G.
        drawRect(
            color = Color(0xFF4285F4),
            topLeft = Offset(size.width * 0.50f, size.height * 0.42f),
            size = Size(size.width * 0.50f, stroke),
        )
    }
}

package cz.digitalnivedomi.diarium.ui.achievements

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.digitalnivedomi.diarium.core.achievements.AchievementCategory
import cz.digitalnivedomi.diarium.core.achievements.AchievementStatus
import cz.digitalnivedomi.diarium.core.achievements.AchievementsData

/** Dark glass palette — same accent indigo as the rest of the app. */
private val BACKGROUND = Color(0xFF0B0B12)
private val ACCENT = Color(0xFF6366F1)
private val CARD_BG = Color(0x14FFFFFF)
private val CARD_BORDER = Color(0x1FFFFFFF)
private val DIALOG_BG = Color(0xFF161623)
private val TEXT_PRIMARY = Color(0xFFF5F5FA)
private val TEXT_MUTED = Color(0xFF9AA0B4)

/**
 * The achievements panel: a grid of badges built from the pure [AchievementsData]
 * the repository hands over — unlocked badges in full colour with their unlock
 * date, locked ones dimmed with their `x/y`, a total "Odemčeno N/M" header, and a
 * tap-through detail with the condition and a progress bar.
 *
 * Load, error and retry are all local: a failed (or absent) [AchievementsDeps.achievements]
 * shows the retry state rather than a grid that would read as "nothing earned".
 * The screen never touches navigation — the parent wires it in.
 */
@Composable
fun AchievementsScreen(deps: AchievementsDeps, modifier: Modifier = Modifier) {
    val repository = deps.achievements
    var state by remember { mutableStateOf<AchievementsUiState>(AchievementsUiState.Loading) }
    var reload by remember { mutableStateOf(0) }
    var selected by remember { mutableStateOf<AchievementStatus?>(null) }

    LaunchedEffect(repository, reload) {
        state = AchievementsUiState.Loading
        state = if (repository == null) {
            AchievementsUiState.Error("Odznaky se nepodařilo načíst. Zkus to znovu.")
        } else {
            try {
                AchievementsUiState.Ready(repository.load())
            } catch (e: Exception) {
                AchievementsUiState.Error(e.message ?: "Odznaky se nepodařilo načíst. Zkus to znovu.")
            }
        }
    }

    Box(modifier = modifier.fillMaxSize().background(BACKGROUND)) {
        when (val current = state) {
            AchievementsUiState.Loading -> LoadingState()
            is AchievementsUiState.Error -> ErrorState(current.message, onRetry = { reload++ })
            is AchievementsUiState.Ready -> ReadyView(current.data, onSelect = { selected = it })
        }
    }

    selected?.let { status ->
        DetailDialog(status = status, onDismiss = { selected = null })
    }
}

@Composable
private fun ReadyView(data: AchievementsData, onSelect: (AchievementStatus) -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Header(data)
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(data.statuses, key = { it.key }) { status ->
                BadgeCard(status = status, onClick = { onSelect(status) })
            }
        }
    }
}

@Composable
private fun Header(data: AchievementsData) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 12.dp),
    ) {
        Text(text = "Odznaky", color = TEXT_PRIMARY, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            text = data.unlockedLabel,
            color = ACCENT,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun BadgeCard(status: AchievementStatus, onClick: () -> Unit) {
    val unlocked = status.unlocked
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (unlocked) 1f else 0.5f)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = CARD_BG),
        border = BorderStroke(1.dp, if (unlocked) ACCENT.copy(alpha = 0.55f) else CARD_BORDER),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(text = status.def.emoji, fontSize = 34.sp)
            Spacer(Modifier.height(8.dp))
            Text(
                text = status.def.name,
                color = TEXT_PRIMARY,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
            Spacer(Modifier.height(6.dp))
            if (unlocked) {
                Text(
                    text = "Odemčeno",
                    color = ACCENT,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
                status.unlockedAt?.let { stamp ->
                    Text(text = formatDate(stamp), color = TEXT_MUTED, fontSize = 11.sp)
                }
            } else {
                Text(text = status.ratio, color = TEXT_MUTED, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                ProgressBar(
                    progress = status.progress.toFloat() / status.target,
                    accent = ACCENT,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun DetailDialog(status: AchievementStatus, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Zavřít") }
        },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = status.def.emoji, fontSize = 28.sp)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = status.def.name,
                    color = TEXT_PRIMARY,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        text = {
            Column {
                Text(
                    text = categoryLabel(status.def.category),
                    color = ACCENT,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(10.dp))
                Text(text = status.def.description, color = TEXT_PRIMARY, fontSize = 14.sp)
                Spacer(Modifier.height(16.dp))
                Text(text = status.ratio, color = TEXT_MUTED, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                ProgressBar(
                    progress = status.progress.toFloat() / status.target,
                    accent = ACCENT,
                    modifier = Modifier.fillMaxWidth(),
                )
                status.unlockedAt?.let { stamp ->
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "Odemčeno ${formatDate(stamp)}",
                        color = TEXT_MUTED,
                        fontSize = 12.sp,
                    )
                }
            }
        },
        containerColor = DIALOG_BG,
        titleContentColor = TEXT_PRIMARY,
        textContentColor = TEXT_PRIMARY,
    )
}

/** A slim rounded bar; drawn with boxes so it needs no experimental progress API. */
@Composable
private fun ProgressBar(progress: Float, accent: Color, modifier: Modifier = Modifier) {
    val fraction = progress.coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0x22FFFFFF)),
    ) {
        if (fraction > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(4.dp))
                    .background(accent),
            )
        }
    }
}

@Composable
private fun LoadingState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = ACCENT)
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = "😕", fontSize = 40.sp)
            Spacer(Modifier.height(12.dp))
            Text(
                text = message,
                color = TEXT_PRIMARY,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(
                    containerColor = ACCENT,
                    contentColor = Color.White,
                ),
            ) {
                Text("Zkusit znovu")
            }
        }
    }
}

/** The category chip in the detail sheet, in Czech like the web labels. */
private fun categoryLabel(category: AchievementCategory): String = when (category) {
    AchievementCategory.STREAK -> "Série"
    AchievementCategory.COUNT -> "Počty"
    AchievementCategory.FEATURE -> "Funkce"
    AchievementCategory.MOOD -> "Nálady"
    AchievementCategory.SPECIAL -> "Speciální"
}

/** `2025-01-02T10:00:00+01:00` → `2025-01-02`; a bad value is shown as-is. */
private fun formatDate(iso: String): String = iso.take(10)

private sealed interface AchievementsUiState {
    object Loading : AchievementsUiState
    data class Ready(val data: AchievementsData) : AchievementsUiState
    data class Error(val message: String) : AchievementsUiState
}

package cz.digitalnivedomi.diarium.ui.checkin

import androidx.compose.ui.graphics.Color

/**
 * The option lists for the three emoji scales, ported verbatim from the web's
 * `MOODS` / `SLEEP_QUALITY` / `STRESS_LEVELS` (emoji + Czech label + colour).
 */
data class EmojiChoice(
    val value: Int,
    val emoji: String,
    val label: String,
    val color: Color,
)

/** cs.ts `mood.mood_*`: Skvěle / Dobře / Jde to / Špatně / Hrozně. */
val MOOD_CHOICES = listOf(
    EmojiChoice(5, "😄", "Skvěle", Color(0xFF22C55E)),
    EmojiChoice(4, "🙂", "Dobře", Color(0xFF3B82F6)),
    EmojiChoice(3, "😐", "Jde to", Color(0xFFEAB308)),
    EmojiChoice(2, "😟", "Špatně", Color(0xFFF97316)),
    EmojiChoice(1, "😡", "Hrozně", Color(0xFFEF4444)),
)

/** cs.ts `mood.sleep_*`: Skvělý / Normální / Špatný. */
val SLEEP_CHOICES = listOf(
    EmojiChoice(3, "😴", "Skvělý", Color(0xFF6366F1)),
    EmojiChoice(2, "🥱", "Normální", Color(0xFF6366F1)),
    EmojiChoice(1, "😪", "Špatný", Color(0xFF6366F1)),
)

/** cs.ts `stress.stress_*`: Nízký / Mírný / Střední / Vysoký / Extrémní. */
val STRESS_CHOICES = listOf(
    EmojiChoice(1, "😌", "Nízký", Color(0xFF6366F1)),
    EmojiChoice(2, "🙂", "Mírný", Color(0xFF6366F1)),
    EmojiChoice(3, "😐", "Střední", Color(0xFF6366F1)),
    EmojiChoice(4, "😰", "Vysoký", Color(0xFF6366F1)),
    EmojiChoice(5, "😤", "Extrémní", Color(0xFF6366F1)),
)

package cz.digitalnivedomi.diarium.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM coverage for the case-insensitive label de-duplication in
 * [PickersRepository] (see `dedupeActivities` / `dedupeHabits`).
 *
 * The real defect: `user_activities` held key `hacking` (label `Hacking`) next
 * to key `hacking_` (label `hacking`), so the check-in's Záliby group showed the
 * activity twice. The data layer now collapses any two rows whose labels differ
 * only by case, so the UI can never render such a pair again. These tests pin
 * the rule (upper-case variant wins, its fields survive, order is the input
 * order of the survivors) so it cannot be removed by accident.
 */
class ActivityLabelDedupeTest {

    @Test
    fun `hacking and Hacking collapse to the capitalised label`() {
        val lowercase = ActivityDef(
            key = "hacking_",
            label = "hacking",
            icon = "⭐",
            category = "vlastní",
            color = "#000000",
            source = "user",
        )
        val capitalised = ActivityDef(
            key = "hacking",
            label = "Hacking",
            icon = "💻",
            category = "záliby",
            color = "#22C55E",
            source = "user",
        )

        // The lower-case row comes first on purpose: the capitalised row must
        // still supply the surviving label.
        val result = dedupeActivities(listOf(lowercase, capitalised))

        assertEquals(1, result.size)
        assertEquals("Hacking", result.single().label)
        assertEquals("hacking", result.single().key)
    }

    @Test
    fun `the merge keeps the icon and colour of the surviving row`() {
        val lowercase = ActivityDef(
            key = "hacking_",
            label = "hacking",
            icon = "⭐",
            category = "vlastní",
            color = "#000000",
        )
        val capitalised = ActivityDef(
            key = "hacking",
            label = "Hacking",
            icon = "💻",
            category = "záliby",
            color = "#22C55E",
        )

        val kept = dedupeActivities(listOf(lowercase, capitalised)).single()

        assertEquals("💻", kept.icon)
        assertEquals("#22C55E", kept.color)
        assertEquals("záliby", kept.category)

        // Same when the capitalised row comes first.
        val keptAgain = dedupeActivities(listOf(capitalised, lowercase)).single()
        assertEquals("Hacking", keptAgain.label)
        assertEquals("💻", keptAgain.icon)
        assertEquals("#22C55E", keptAgain.color)
        assertEquals("záliby", keptAgain.category)
    }

    @Test
    fun `keys are irrelevant, only the label decides a duplicate`() {
        val result = dedupeActivities(
            listOf(
                ActivityDef(key = "hacking", label = "Hacking", icon = "💻"),
                ActivityDef(key = "hacking_", label = "hacking", icon = "⭐"),
                ActivityDef(key = "another_key", label = "HACKING", icon = "🖥️"),
            ),
        )

        assertEquals(1, result.size)
        // First upper-case variant wins; `Hacking` starts upper-case, `HACKING`
        // is a later upper-case variant so the earlier one is kept.
        assertEquals("Hacking", result.single().label)
        assertEquals("💻", result.single().icon)
    }

    @Test
    fun `with no capitalised variant the first row wins`() {
        val first = ActivityDef(key = "snooker", label = "snooker", icon = "🎱", color = "#111111")
        val second = ActivityDef(key = "snooker_", label = "snooker", icon = "⭐", color = "#222222")

        val kept = dedupeActivities(listOf(first, second)).single()

        assertEquals("snooker", kept.label)
        assertEquals("🎱", kept.icon)
        assertEquals("#111111", kept.color)
    }

    @Test
    fun `unrelated labels are untouched`() {
        val items = listOf(
            ActivityDef(key = "rodina", label = "Rodina", icon = "👨‍👩‍👧"),
            ActivityDef(key = "hacking", label = "Hacking", icon = "💻"),
            ActivityDef(key = "hacking_", label = "hacking", icon = "⭐"),
            ActivityDef(key = "snooker", label = "Snooker", icon = "🎱"),
        )

        val result = dedupeActivities(items)

        assertEquals(listOf("Rodina", "Hacking", "Snooker"), result.map { it.label })
    }

    @Test
    fun `the result keeps the input order of the surviving rows`() {
        val items = listOf(
            ActivityDef(key = "hacking_", label = "hacking", icon = "⭐"),  // index 0, replaced by index 2
            ActivityDef(key = "rodina", label = "Rodina", icon = "👨‍👩‍👧"), // index 1, survives
            ActivityDef(key = "hacking", label = "Hacking", icon = "💻"),   // index 2, survivor
            ActivityDef(key = "snooker", label = "Snooker", icon = "🎱"),   // index 3, survives
        )

        val result = dedupeActivities(items)

        assertEquals(listOf("Rodina", "Hacking", "Snooker"), result.map { it.label })
    }

    @Test
    fun `accented labels collapse case-insensitively`() {
        val result = dedupeActivities(
            listOf(
                ActivityDef(key = "cteni", label = "Čtení", icon = "📖"),
                ActivityDef(key = "cteni_", label = "čtení", icon = "⭐"),
            ),
        )

        assertEquals(1, result.size)
        assertEquals("Čtení", result.single().label)
        assertEquals("📖", result.single().icon)
    }

    @Test
    fun `blank and single-element inputs stay as they are`() {
        assertEquals(emptyList<ActivityDef>(), dedupeActivities(emptyList()))

        val only = ActivityDef(key = "rodina", label = "Rodina", icon = "👨‍👩‍👧")
        assertEquals(listOf(only), dedupeActivities(listOf(only)))
    }

    // ── habits: symmetric treatment ─────────────────────────────────────────

    @Test
    fun `habit labels collapse the same way, keeping the surviving row's fields`() {
        val lowercase = HabitDef(
            key = "alkohol_",
            label = "alkohol",
            icon = "⭐",
            color = "#000000",
            isNegative = true,
        )
        val capitalised = HabitDef(
            key = "alkohol",
            label = "Alkohol",
            icon = "🍺",
            color = "#ef4444",
            isNegative = true,
        )

        val kept = dedupeHabits(listOf(lowercase, capitalised)).single()

        assertEquals("Alkohol", kept.label)
        assertEquals("🍺", kept.icon)
        assertEquals("#ef4444", kept.color)
    }

    @Test
    fun `the positive-negative flag is never flipped by the merge`() {
        val negative = HabitDef(key = "porno", label = "Porno", icon = "🔞", isNegative = true)
        val positive = HabitDef(key = "porno_", label = "porno", icon = "⭐", isNegative = false)

        val kept = dedupeHabits(listOf(positive, negative)).single()

        assertEquals("Porno", kept.label)
        assertTrue("isNegative must come from the surviving row, not be reset", kept.isNegative)
    }

    @Test
    fun `habit survivors keep their relative order`() {
        val items = listOf(
            HabitDef(key = "alkohol", label = "Alkohol", icon = "🍺"),
            HabitDef(key = "spanek_", label = "spanek", icon = "⭐"),
            HabitDef(key = "spanek", label = "Spanek", icon = "😴"),
            HabitDef(key = "kava", label = "Káva", icon = "☕"),
        )

        val result = dedupeHabits(items)

        assertEquals(listOf("Alkohol", "Spanek", "Káva"), result.map { it.label })
        assertEquals(listOf("🍺", "😴", "☕"), result.map { it.icon })
    }
}

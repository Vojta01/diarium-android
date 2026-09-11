package cz.digitalnivedomi.diarium.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM coverage for the case-insensitive stored-value matching helpers in
 * [PickerDefaults] (`normalizeLabel`, `labelsMatch`, `isStoredLabel`).
 *
 * Entries store the chosen activities as a JSON array of LABEL STRINGS, e.g.
 * `["Rodina", "hacking"]`. A day saved before the duplicate `user_activities`
 * rows were cleaned up therefore holds `hacking`, while the picker now offers
 * the surviving `Hacking` chip. Matching has to ignore case so the chip still
 * renders selected, and the stored strings must never be rewritten to make that
 * happen.
 */
class StoredLabelMatchTest {

    @Test
    fun `case-insensitive matching is symmetric for hacking and Hacking`() {
        assertTrue(PickerDefaults.labelsMatch("hacking", "Hacking"))
        assertTrue(PickerDefaults.labelsMatch("Hacking", "hacking"))
    }

    @Test
    fun `exact and differently-cased labels still match`() {
        assertTrue(PickerDefaults.labelsMatch("Hacking", "Hacking"))
        assertTrue(PickerDefaults.labelsMatch("Čtení", "čtení"))
        assertTrue(PickerDefaults.labelsMatch("Čtení", "ČTENÍ"))
    }

    @Test
    fun `unrelated labels do not match`() {
        assertFalse(PickerDefaults.labelsMatch("Hacking", "Snooker"))
        assertFalse(PickerDefaults.labelsMatch("Rodina", "Hacking"))
        assertFalse(PickerDefaults.labelsMatch("", "Hacking"))
    }

    @Test
    fun `normalizeLabel trims surrounding space and lower-cases`() {
        assertEquals("hacking", PickerDefaults.normalizeLabel("Hacking"))
        assertEquals("hacking", PickerDefaults.normalizeLabel("  hacking  "))
        assertEquals("volný čas", PickerDefaults.normalizeLabel(" VOLNÝ ČAS "))
    }

    @Test
    fun `isStoredLabel finds a stored lowercase value against the capitalised chip`() {
        val stored = listOf("Rodina", "hacking", "Snooker")

        assertTrue(PickerDefaults.isStoredLabel(stored, "Hacking"))
    }

    @Test
    fun `isStoredLabel finds a capitalised stored value against the lowercase chip`() {
        assertTrue(PickerDefaults.isStoredLabel(listOf("Hacking"), "hacking"))
    }

    @Test
    fun `isStoredLabel is false for a label the entry never held`() {
        val stored = listOf("Rodina", "Snooker")

        assertFalse(PickerDefaults.isStoredLabel(stored, "Hacking"))
        assertFalse(PickerDefaults.isStoredLabel(emptyList(), "Hacking"))
    }

    @Test
    fun `matching never rewrites the stored entry`() {
        val stored = listOf("Rodina", "hacking", "Snooker")

        assertTrue(PickerDefaults.isStoredLabel(stored, "Hacking"))
        // The stored strings are untouched — comparison only.
        assertEquals(listOf("Rodina", "hacking", "Snooker"), stored)
    }
}

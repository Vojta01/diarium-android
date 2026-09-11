package cz.digitalnivedomi.diarium.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the note-template rules. No Android framework, no
 * Robolectric, no network: [applyTemplate], [needsConfirm] and
 * [TemplateDefaults.DEFAULTS] are the parts that decide what the user's note
 * becomes, so they are asserted directly here.
 */
class TemplatesRepositoryTest {

    // ── applyTemplate: insert REPLACES, never appends ───────────────────────

    @Test
    fun `applyTemplate replaces a non-empty note completely`() {
        val current = "Dlouhý zápis z dneška, který má zmizet."
        val template = "Dnes ráno se cítím...\n\nCo dnes chci dokázat:"

        val result = applyTemplate(current, template)

        assertEquals(template, result)
        // The old text must not survive anywhere — not before, not after.
        assertFalse(result.contains(current))
        assertFalse(result.contains("má zmizet"))
    }

    @Test
    fun `applyTemplate never concatenates old and new text`() {
        val result = applyTemplate("Původní poznámka", "Nová šablona")
        assertEquals("Nová šablona", result)
        assertFalse(result.startsWith("Původní"))
        assertFalse(result.endsWith("Původní poznámka"))
        assertTrue(result == "Nová šablona")
    }

    @Test
    fun `applyTemplate on an empty note returns exactly the template`() {
        val template = "Dnešek byl..."
        assertEquals(template, applyTemplate("", template))
    }

    @Test
    fun `applyTemplate on a whitespace-only note returns exactly the template`() {
        val template = "Dnešek byl..."
        assertEquals(template, applyTemplate("   \n\t ", template))
    }

    // ── needsConfirm ────────────────────────────────────────────────────────

    @Test
    fun `needsConfirm is false for an empty note`() {
        assertFalse(needsConfirm(""))
    }

    @Test
    fun `needsConfirm is false for a whitespace-only note`() {
        assertFalse(needsConfirm(" "))
        assertFalse(needsConfirm("\n\n"))
        assertFalse(needsConfirm(" \t \n  "))
    }

    @Test
    fun `needsConfirm is true for real text`() {
        assertTrue(needsConfirm("a"))
        assertTrue(needsConfirm("  něco  "))
        assertTrue(needsConfirm("\nDnes bylo dobře.\n"))
    }

    // ── the three built-in defaults ─────────────────────────────────────────

    @Test
    fun `there are exactly three default templates in sort order 1 to 3`() {
        assertEquals(3, TemplateDefaults.DEFAULTS.size)
        assertEquals(listOf(1, 2, 3), TemplateDefaults.DEFAULTS.map { it.sortOrder })
    }

    @Test
    fun `default names and contents match the web contract exactly`() {
        val expected = listOf(
            TemplateDefault(
                name = "🌅 Ranní reflexe",
                content = "Dnes ráno se cítím...\n\n3 věci, na které se těším:\n1. \n2. \n3. \n\nCo dnes chci dokázat:",
                sortOrder = 1,
            ),
            TemplateDefault(
                name = "🌙 Večerní shrnutí",
                content = "Dnešek byl...\n\nNejlepší moment dne:\n\nCo bych zlepšil/a:\n\nZa co jsem vděčný/á:",
                sortOrder = 2,
            ),
            TemplateDefault(
                name = "💪 Těžký den",
                content = "Dnes to bylo těžké, protože...\n\nCo mě drží při životě:\n\nZítra bude líp, protože:",
                sortOrder = 3,
            ),
        )
        assertEquals(expected, TemplateDefaults.DEFAULTS)
    }

    @Test
    fun `asNoteTemplates keeps the defaults name content and order`() {
        val templates = TemplateDefaults.asNoteTemplates()
        assertEquals(TemplateDefaults.DEFAULTS.map { it.name }, templates.map { it.name })
        assertEquals(TemplateDefaults.DEFAULTS.map { it.content }, templates.map { it.content })
        assertEquals(listOf(1, 2, 3), templates.map { it.sortOrder })
    }

    // ── firstLinePreview ────────────────────────────────────────────────────

    @Test
    fun `firstLinePreview uses the first non-blank line`() {
        assertEquals("Dnešek byl...", firstLinePreview("Dnešek byl...\n\nNejlepší moment dne:"))
        assertEquals("Ahoj", firstLinePreview("\n\n  Ahoj  \nZbytek"))
        assertEquals("", firstLinePreview("\n   \n"))
    }

    @Test
    fun `firstLinePreview clips long lines`() {
        val preview = firstLinePreview("x".repeat(200))
        assertTrue(preview.endsWith("…"))
        assertTrue(preview.length <= 80)
    }
}

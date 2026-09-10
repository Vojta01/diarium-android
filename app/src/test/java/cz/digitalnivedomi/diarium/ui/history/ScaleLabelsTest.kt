package cz.digitalnivedomi.diarium.ui.history

import cz.digitalnivedomi.diarium.core.data.Scale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The history day detail shows an entry's `scale_values`, which is keyed by the
 * scale row's uuid. These helpers are what turn that uuid back into something a
 * reader recognises ("⚡ Energie — 3 / 5"), and they are pure functions so the
 * mapping is pinned here rather than only through a screenshot.
 */
class ScaleLabelsTest {

    private val energy = Scale(
        id = "3be23c69-48fe-45c7-b9bf-559552b651b7",
        name = "Energie",
        emoji = "⚡",
        minValue = 1,
        maxValue = 5,
    )
    private val productivity = Scale(
        id = "60607076-9a11-4c3d-8f5e-2b2c1d0e4f77",
        name = "Produktivita",
        emoji = "💪",
        minValue = 1,
        maxValue = 10,
    )

    @Test
    fun `names an entry's scale key with the scale's emoji and name`() {
        val names = scaleNameMap(listOf(energy, productivity))

        assertEquals("⚡ Energie", names[energy.id])
        assertEquals("💪 Produktivita", names[productivity.id])
    }

    @Test
    fun `maps every scale id to its own maximum`() {
        val maxima = scaleMaxMap(listOf(energy, productivity))

        assertEquals(5, maxima[energy.id])
        assertEquals(10, maxima[productivity.id])
    }

    @Test
    fun `shows the value against the resolved scale's maximum, not a hardcoded five`() {
        val maxima = scaleMaxMap(listOf(energy, productivity))

        assertEquals("3 / 5", scaleValueText(3, energy.id, maxima))
        assertEquals("7 / 10", scaleValueText(7, productivity.id, maxima))
    }

    @Test
    fun `falls back to the old slash five when the scale behind the key is gone`() {
        // An entry written before the scales were read, or one whose scale row was
        // deleted since, must still show its number.
        assertEquals("4 / 5", scaleValueText(4, "deleted-scale-id", emptyMap()))
        assertNull(scaleNameMap(emptyList())["deleted-scale-id"])
    }
}

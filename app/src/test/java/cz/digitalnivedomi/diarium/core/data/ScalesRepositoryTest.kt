package cz.digitalnivedomi.diarium.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the seed plan and the 30-day read-out maths. No Android
 * framework, no Robolectric, no network — everything under test here is a pure
 * function, which is exactly why it was factored out of [ScalesRepository].
 */
class ScalesRepositoryTest {

    // ── distribution ────────────────────────────────────────────────────────

    @Test
    fun distributionCountsEveryOccurrence() {
        assertEquals(
            mapOf(1 to 1, 3 to 2, 5 to 3),
            distribution(listOf(3, 3, 5, 1, 5, 5)),
        )
    }

    @Test
    fun distributionOmitsValuesThatNeverOccur() {
        val counts = distribution(listOf(3, 3, 5))
        // 4 is inside the 1..5 range but never occurred: no key is invented,
        // and the UI's `?: 0` reads it as zero.
        assertEquals(null, counts[4])
        assertEquals(0, counts[4] ?: 0)
        assertEquals(setOf(3, 5), counts.keys)
    }

    @Test
    fun distributionOfEmptyListIsEmpty() {
        assertTrue(distribution(emptyList()).isEmpty())
    }

    // ── average ─────────────────────────────────────────────────────────────

    @Test
    fun averageOfEmptyListIsExactlyZero() {
        assertEquals(0.0, average(emptyList()), 0.0)
    }

    @Test
    fun averageIsTheExactMeanAndIsNotRounded() {
        // 5 / 3 must NOT come back as 1.67 or 2 — the mean stays exact and is
        // rounded only once, for display, by formatAverage.
        assertEquals(5.0 / 3.0, average(listOf(1, 2, 2)), 1e-9)
        assertEquals(2.5, average(listOf(1, 4)), 0.0)
        assertEquals(3.0, average(listOf(3)), 0.0)
    }

    @Test
    fun formatAverageRoundsHalfUpToOneDecimalWithCzechComma() {
        assertEquals("1,7", formatAverage(5.0 / 3.0))
        assertEquals("2,0", formatAverage(2.0))
        assertEquals("2,5", formatAverage(2.5))
        // Half-up, not banker's rounding and not truncation.
        assertEquals("2,3", formatAverage(2.25))
        assertEquals("0,0", formatAverage(0.0))
        assertEquals("4,1", formatAverage(4.05))
    }

    // ── seed decisions ──────────────────────────────────────────────────────

    @Test
    fun defaultsMatchTheWebContractExactly() {
        assertEquals(2, DEFAULT_SCALES.size)
        assertEquals(
            ScaleDraft("Energie", "⚡", 1, 5, "#eab308", 0),
            DEFAULT_SCALES[0],
        )
        assertEquals(
            ScaleDraft("Produktivita", "💪", 1, 5, "#22c55e", 1),
            DEFAULT_SCALES[1],
        )
    }

    @Test
    fun emptyTableCreatesBothDefaultsAndDeletesNothing() {
        val plan = planSeed(emptyList())
        assertEquals(DEFAULT_SCALES, plan.toCreate)
        assertTrue(plan.toDeleteIds.isEmpty())
    }

    @Test
    fun matchingScalesAreUntouched() {
        val existing = listOf(
            scale("e1", "Energie", 1, 5),
            scale("p1", "Produktivita", 1, 5),
        )
        val plan = planSeed(existing)
        assertTrue(plan.toCreate.isEmpty())
        assertTrue(plan.toDeleteIds.isEmpty())
    }

    @Test
    fun renamedOrMisspelledScaleIsDeleted() {
        val existing = listOf(
            scale("e1", "Energie", 1, 5),
            scale("p1", "Produktivita", 1, 5),
            scale("x1", "Nálada", 1, 5),
            scale("x2", "Energiee", 1, 5),
        )
        val plan = planSeed(existing)
        assertEquals(listOf("x1", "x2"), plan.toDeleteIds)
        assertTrue(plan.toCreate.isEmpty())
    }

    @Test
    fun missingDefaultIsCreated() {
        val existing = listOf(scale("e1", "Energie", 1, 5))
        val plan = planSeed(existing)
        assertEquals(listOf(DEFAULT_SCALES[1]), plan.toCreate)
        assertTrue(plan.toDeleteIds.isEmpty())
    }

    @Test
    fun nameMatchIsCaseInsensitiveAndTrimmed() {
        val existing = listOf(
            scale("e1", "  energie ", 1, 5),
            scale("p1", "PRODUKTIVITA", 1, 5),
        )
        val plan = planSeed(existing)
        assertTrue(plan.toCreate.isEmpty())
        assertTrue(plan.toDeleteIds.isEmpty())
    }

    @Test
    fun driftedRangeIsDeletedAndRecreated() {
        val existing = listOf(
            scale("e1", "Energie", 1, 10),
            scale("p1", "Produktivita", 1, 5),
        )
        val plan = planSeed(existing)
        assertEquals(listOf("e1"), plan.toDeleteIds)
        assertEquals(listOf(DEFAULT_SCALES[0]), plan.toCreate)
    }

    @Test
    fun duplicateRowsWithADefaultNameLoseAllButTheFirst() {
        val existing = listOf(
            scale("e1", "Energie", 1, 5),
            scale("e2", "energie", 1, 5),
            scale("p1", "Produktivita", 1, 5),
        )
        val plan = planSeed(existing)
        assertEquals(listOf("e2"), plan.toDeleteIds)
        assertTrue(plan.toCreate.isEmpty())
    }

    private fun scale(id: String, name: String, min: Int, max: Int): Scale =
        Scale(id = id, name = name, emoji = "⚡", minValue = min, maxValue = max, color = "#eab308")
}

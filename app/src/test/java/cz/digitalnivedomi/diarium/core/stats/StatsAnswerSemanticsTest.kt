package cz.digitalnivedomi.diarium.core.stats

import org.junit.Assert.assertEquals
import org.junit.Test

class StatsAnswerSemanticsTest {
    @Test fun `habit defaults are unmet only on recorded eligible dates`() {
        assertEquals(HabitOutcome.UNMET, habitOutcome(true, HabitEligibility.ELIGIBLE, false))
        assertEquals(HabitOutcome.UNMET, habitOutcome(true, HabitEligibility.ELIGIBLE, null))
        assertEquals(HabitOutcome.MET, habitOutcome(true, HabitEligibility.ELIGIBLE, true))
    }

    @Test fun `missing entries and unproven or disabled eligibility stay unknown`() {
        assertEquals(HabitOutcome.UNKNOWN, habitOutcome(false, HabitEligibility.ELIGIBLE, false))
        assertEquals(HabitOutcome.UNKNOWN, habitOutcome(true, HabitEligibility.UNKNOWN, null))
        assertEquals(HabitOutcome.UNKNOWN, habitOutcome(true, HabitEligibility.NOT_YET_ELIGIBLE, false))
        assertEquals(HabitOutcome.UNKNOWN, habitOutcome(true, HabitEligibility.DISABLED, false))
    }

    @Test fun `sleep labels match check-in order and zero is unanswered`() {
        assertEquals("Špatný", sleepAnswerLabel(1))
        assertEquals("Normální", sleepAnswerLabel(2))
        assertEquals("Skvělý", sleepAnswerLabel(3))
        assertEquals("Bez odpovědi", sleepAnswerLabel(0))
        assertEquals("Neznámá hodnota", sleepAnswerLabel(4))
    }

    @Test fun `stress labels match check-in order and zero is unanswered`() {
        assertEquals("Nízký", stressAnswerLabel(1))
        assertEquals("Mírný", stressAnswerLabel(2))
        assertEquals("Střední", stressAnswerLabel(3))
        assertEquals("Vysoký", stressAnswerLabel(4))
        assertEquals("Extrémní", stressAnswerLabel(5))
        assertEquals("Bez odpovědi", stressAnswerLabel(0))
        assertEquals("Neznámá hodnota", stressAnswerLabel(6))
    }

    @Test fun `custom scales use actual bounds without invented intermediate labels`() {
        assertEquals("1 (minimum)", numericScaleAnswerLabel(1, 1, 5))
        assertEquals("3 / 5", numericScaleAnswerLabel(3, 1, 5))
        assertEquals("5 (maximum)", numericScaleAnswerLabel(5, 1, 5))
        assertEquals("2 (minimum)", numericScaleAnswerLabel(2, 2, 10))
        assertEquals("10 (maximum)", numericScaleAnswerLabel(10, 2, 10))
    }

    @Test fun `missing zero invalid and out-of-range scale answers are distinct from minimum`() {
        assertEquals("Bez odpovědi", numericScaleAnswerLabel(null, 1, 5))
        assertEquals("Bez odpovědi", numericScaleAnswerLabel(0, 1, 5))
        assertEquals("Neznámá hodnota", numericScaleAnswerLabel(6, 1, 5))
        assertEquals("Neznámá hodnota", numericScaleAnswerLabel(2, 5, 1))
    }
}

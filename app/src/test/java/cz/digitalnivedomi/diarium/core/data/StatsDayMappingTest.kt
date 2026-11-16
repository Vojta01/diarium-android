package cz.digitalnivedomi.diarium.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StatsDayMappingTest {
    @Test fun `entry stats mapping retains explicit negative habit, answered scales, and nullable sync`() {
        val entry = DiaryEntry(
            mood = 0, habits = mapOf("walk" to false), sleepQuality = 4, stress = 2,
            scaleValues = mapOf("energy" to 3, "cleared" to 0), phoneScreenTime = null,
        )
        val day = StatsRepository.toStatsDay("2024-02-29", entry)
        assertEquals(mapOf("walk" to false), day.habits)
        assertEquals(4, day.sleepQuality)
        assertEquals(2, day.stress)
        assertEquals(mapOf("energy" to 3), day.scaleValues)
        assertNull(day.screenTimeSeconds)
        assertEquals(false, day.habits["walk"])
    }

    @Test fun `legacy constructor defaults do not invent extra stats`() {
        val day = StatsRepository.toStatsDay("2024-01-01", DiaryEntry())
        assertEquals(emptyMap<String, Boolean>(), day.habits)
        assertEquals(0, day.sleepQuality)
        assertEquals(0, day.stress)
        assertEquals(emptyMap<String, Int>(), day.scaleValues)
        assertNull(day.screenTimeSeconds)
    }
}

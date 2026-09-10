package cz.digitalnivedomi.diarium.ui.checkin.components

import cz.digitalnivedomi.diarium.core.data.ActivityDef
import cz.digitalnivedomi.diarium.core.data.PickerDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Pure JVM coverage for the check-in activity grouping. The `záliby` alias has
 * to fold into `volný čas` so the form shows one Záliby group, and the weather
 * slice stays out because [WeatherSection] renders it.
 */
class ActivitiesGroupingTest {

    @Test
    fun `canonicalCategory folds aliases and casing`() {
        assertEquals("volný čas", PickerDefaults.canonicalCategory("záliby"))
        assertEquals("volný čas", PickerDefaults.canonicalCategory(" zaliby "))
        assertEquals("volný čas", PickerDefaults.canonicalCategory("VOLNÝ ČAS"))
        assertEquals("volný čas", PickerDefaults.canonicalCategory("volny cas"))
        assertEquals("sport", PickerDefaults.canonicalCategory("Sport"))
        assertEquals("", PickerDefaults.canonicalCategory("   "))
    }

    @Test
    fun `zaliby and volny cas merge into one group titled Zaliby`() {
        val defs = listOf(
            ActivityDef(key = "hacking", label = "Hacking", icon = "💻", category = "záliby"),
            ActivityDef(key = "cteni", label = "Čtení", icon = "📖", category = "volný čas"),
            ActivityDef(key = "sport", label = "Sport", icon = "🏃", category = "sport"),
        )

        val grouped = groupedByCategory(defs)
        val hobbies = grouped.single { it.first == "volný čas" }

        assertEquals(listOf("Hacking", "Čtení"), hobbies.second.map { it.label })
        assertEquals("Záliby", PickerDefaults.categoryLabel(hobbies.first))
    }

    @Test
    fun `weather is excluded because WeatherSection owns it`() {
        val defs = listOf(
            ActivityDef(key = "slunecno", label = "Slunečno", icon = "☀️", category = "počasí"),
            ActivityDef(key = "sport", label = "Sport", icon = "🏃", category = "sport"),
        )

        val grouped = groupedByCategory(defs)
        assertFalse(grouped.any { it.first == PickerDefaults.WEATHER_CATEGORY })
        assertEquals(listOf("sport"), grouped.map { it.first })
    }

    @Test
    fun `known categories follow CATEGORY_ORDER and unknown ones sort last`() {
        val defs = listOf(
            ActivityDef(key = "weird", label = "Weird", icon = "❓", category = "neznámé"),
            ActivityDef(key = "rodina", label = "Rodina", icon = "👨", category = "sociální"),
            ActivityDef(key = "filmy", label = "Filmy a TV", icon = "🎬", category = "volný čas"),
        )

        assertEquals(
            listOf("sociální", "volný čas", "neznámé"),
            groupedByCategory(defs).map { it.first },
        )
    }

    @Test
    fun `blank category falls back to obecne`() {
        val defs = listOf(ActivityDef(key = "x", label = "X", icon = "", category = ""))
        assertEquals(listOf("obecné"), groupedByCategory(defs).map { it.first })
    }
}

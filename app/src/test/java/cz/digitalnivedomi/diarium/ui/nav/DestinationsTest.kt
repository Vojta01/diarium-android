package cz.digitalnivedomi.diarium.ui.nav

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Navigation is the spine of the app: if a route is duplicated or the bottom bar
 * loses a tab, every screen-level test downstream becomes meaningless.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DestinationsTest {

    @Test
    fun `bottom bar shows the four top-level destinations in order`() {
        assertEquals(
            listOf(Routes.CHECK_IN, Routes.HISTORY, Routes.STATS, Routes.SETTINGS),
            TopLevelDestination.entries.map { it.route },
        )
    }

    @Test
    fun `routes are unique`() {
        val routes = TopLevelDestination.entries.map { it.route }
        assertEquals("no duplicate routes", routes.size, routes.toSet().size)
    }

    @Test
    fun `auth route is not a bottom-bar tab`() {
        assertTrue(Routes.AUTH !in TopLevelDestination.entries.map { it.route })
    }

    @Test
    fun `every tab has a label and an icon`() {
        TopLevelDestination.entries.forEach { destination ->
            assertTrue("label for ${destination.route}", destination.label.isNotBlank())
            assertTrue("icon for ${destination.route}", destination.selectedIcon.name.isNotBlank())
        }
    }
}

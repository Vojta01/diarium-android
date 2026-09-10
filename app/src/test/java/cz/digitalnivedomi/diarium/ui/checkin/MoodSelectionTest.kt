package cz.digitalnivedomi.diarium.ui.checkin

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import cz.digitalnivedomi.diarium.ui.checkin.components.MoodSection
import cz.digitalnivedomi.diarium.ui.checkin.components.SleepSection
import cz.digitalnivedomi.diarium.ui.theme.DiariumTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Drives the real Compose sections on the JVM: a tap on an emoji must land in
 * the form state (and therefore in the payload the repository builds).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class MoodSelectionTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `selecting a mood updates the form state`() {
        val holder = CheckInStateHolder(initialDate = "2026-09-10")

        compose.setContent {
            DiariumTheme {
                MoodSection(
                    selected = holder.state.entry.mood,
                    onSelect = { value, emoji -> holder.selectMood(value, emoji) },
                )
            }
        }

        compose.onNodeWithTag("mood_5").performClick()
        assertEquals(5, holder.state.entry.mood)
        assertEquals("😄", holder.state.entry.moodEmoji)

        // Re-selecting replaces the previous value instead of accumulating.
        compose.onNodeWithTag("mood_2").performClick()
        assertEquals(2, holder.state.entry.mood)
        assertEquals("😟", holder.state.entry.moodEmoji)
    }

    @Test
    fun `selecting a mood clears the saved flag so autosave resumes`() {
        val holder = CheckInStateHolder(initialDate = "2026-09-10")
        holder.markSaved()
        assertTrue(holder.state.saved)

        compose.setContent {
            DiariumTheme {
                MoodSection(selected = 0, onSelect = { value, emoji -> holder.selectMood(value, emoji) })
            }
        }
        compose.onNodeWithTag("mood_4").performClick()

        assertFalse(holder.state.saved)
        assertEquals(4, holder.state.entry.mood)
    }

    @Test
    fun `sleep selection lands in the entry`() {
        val holder = CheckInStateHolder(initialDate = "2026-09-10")

        compose.setContent {
            DiariumTheme {
                SleepSection(selected = holder.state.entry.sleepQuality, onSelect = holder::setSleep)
            }
        }
        compose.onNodeWithTag("sleep_3").performClick()

        assertEquals(3, holder.state.entry.sleepQuality)
    }
}

package cz.digitalnivedomi.diarium.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import cz.digitalnivedomi.diarium.ui.theme.DiariumTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class MoodTrendChartInteractionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val slots = listOf(
        MoodTrendSlot("2026-03-01", "1", "Day one", 4, true, "Good", emptyList()),
        MoodTrendSlot("2026-03-02", "2", "Missing day", 0, false, "No answer", emptyList()),
        MoodTrendSlot("2026-03-03", "3", "Day three", 2, true, "Okay", emptyList()),
    )

    @Test
    fun `coordinate mapping clamps edges and preserves missing date slot`() {
        assertEquals(0, moodTrendIndexAtX(-10f, 300f, 3))
        assertEquals(1, moodTrendIndexAtX(150f, 300f, 3))
        assertEquals(2, moodTrendIndexAtX(300f, 300f, 3))
        assertNull(moodTrendIndexAtX(10f, 0f, 3))
        assertNull(moodTrendIndexAtX(10f, 100f, 0))
    }

    @Test
    fun `drag scrubs to edge and blank day can be selected without invented average`() {
        compose.setContent {
            DiariumTheme {
                MoodTrendChart(
                    slots = slots,
                    average = listOf(4.0, null, 3.0),
                    averageLabel = "Average",
                    averageDetailLabel = "Daily average",
                    caption = MoodTrendCaption.EdgeDates,
                )
            }
        }

        compose.onNodeWithTag("mood_trend_chart_track").performTouchInput {
            down(Offset(2f, centerY))
            moveTo(Offset(visibleSize.width - 2f, centerY), delayMillis = 300)
            up()
        }
        compose.onNodeWithText("Day three").assertExists()

        // Selecting the empty middle slot displays its own no-answer detail, but no
        // average row: null source data must not be interpolated.
        compose.onNodeWithTag("mood_trend_chart_track").performTouchInput {
            down(Offset(visibleSize.width * 0.5f, centerY))
            up()
        }
        compose.onNodeWithText("Missing day").assertExists()
        compose.onNodeWithText("Daily average").assertDoesNotExist()
    }

    @Test
    fun `tap pins selection and detail action opens exact selected date`() {
        var opened: String? = null
        compose.setContent {
            DiariumTheme {
                MoodTrendChart(
                    slots = slots,
                    average = listOf(4.0, null, 3.0),
                    averageLabel = "Average",
                    averageDetailLabel = "Daily average",
                    caption = MoodTrendCaption.EdgeDates,
                    onOpenDay = { opened = it },
                )
            }
        }

        compose.onNodeWithTag("mood_trend_chart_track").performTouchInput {
            down(Offset(visibleSize.width * 0.5f, centerY))
            up()
        }
        compose.onNodeWithText("Missing day").assertExists()
        // A second tap pins the same day; it does not toggle the detail closed.
        compose.onNodeWithTag("mood_trend_chart_track").performTouchInput {
            down(Offset(visibleSize.width * 0.5f, centerY))
            up()
        }
        compose.onNodeWithText("Missing day").assertExists()
        compose.onNodeWithText("Otevřít celý den ›").performClick()
        compose.runOnIdle { assertEquals("2026-03-02", opened) }
    }
}

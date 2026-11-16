package cz.digitalnivedomi.diarium.ui.stats

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import cz.digitalnivedomi.diarium.ui.theme.DiariumTheme
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class YearInPixelsInteractionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `tapping an unanswered pixel dispatches its exact date`() {
        var opened: String? = null
        compose.setContent {
            DiariumTheme {
                YearInPixels(
                    days = emptyList(),
                    year = 2026,
                    today = "2026-11-15",
                    onOpenDay = { opened = it },
                )
            }
        }

        compose.onNodeWithTag("year_pixel_2026-02-03").performClick()
        compose.runOnIdle { assertEquals("2026-02-03", opened) }
    }

    @Test
    fun `future pixel is disabled and has a spoken date and mood label`() {
        var opened: String? = null
        compose.setContent {
            DiariumTheme {
                YearInPixels(
                    days = listOf(cz.digitalnivedomi.diarium.core.stats.StatsDay(date = "2026-12-02", mood = 4)),
                    year = 2026,
                    today = "2026-11-15",
                    onOpenDay = { opened = it },
                )
            }
        }

        compose.onNodeWithTag("year_pixel_2026-12-02").assertIsNotEnabled().assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription))
        compose.onNodeWithTag("year_pixel_2026-12-02").performClick()
        compose.runOnIdle { assertEquals(null, opened) }
    }

    @Test
    fun `long pressing a pixel dispatches its history month`() {
        var opened: YearMonth? = null
        compose.setContent {
            DiariumTheme {
                YearInPixels(
                    days = emptyList(),
                    year = 2026,
                    today = "2026-11-15",
                    onOpenHistoryMonth = { opened = it },
                )
            }
        }

        compose.onNodeWithTag("year_pixel_2026-02-03").performTouchInput {
            down(center)
            advanceEventTime(1_000L)
            up()
        }
        compose.runOnIdle { assertEquals(YearMonth.of(2026, 2), opened) }
    }
}
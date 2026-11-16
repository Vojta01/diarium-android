package cz.digitalnivedomi.diarium.ui.stats

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import cz.digitalnivedomi.diarium.ui.theme.DiariumTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class CustomDateRangeApplyUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun `editing custom dates does not apply them until apply button is pressed`() {
        var start by mutableStateOf("2026-08-01")
        var end by mutableStateOf("2026-08-07")
        var applyCount = 0
        compose.setContent {
            DiariumTheme {
                CustomDateRangeControl(start, end, error = null,
                    onChange = { newStart, newEnd -> start = newStart; end = newEnd },
                    onApply = { applyCount++ })
            }
        }

        compose.onNodeWithTag("stats_custom_start").performTextReplacement("2026-09-01")
        compose.onNodeWithTag("stats_custom_end").performTextReplacement("2026-09-10")
        compose.runOnIdle {
            assertEquals("2026-09-01", start)
            assertEquals("2026-09-10", end)
            assertEquals("typing alone must not apply", 0, applyCount)
        }
        compose.onNodeWithTag("stats_custom_apply").performClick()
        compose.runOnIdle { assertEquals(1, applyCount) }
    }
}

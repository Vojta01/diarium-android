package cz.digitalnivedomi.diarium.ui.stats

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import cz.digitalnivedomi.diarium.core.data.PhoneTopApp
import cz.digitalnivedomi.diarium.core.stats.StatsDay
import cz.digitalnivedomi.diarium.ui.theme.DiariumTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class ScreenTimeChartsSelectionUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun `aggregate drills to selected source day without invoking check-in and tap toggles clear`() {
        val today = "2026-02-05"
        val sourceDays = (0..34).map { offset ->
            val date = LocalDate.parse("2026-01-01").plusDays(offset.toLong()).toString()
            StatsDay(
                date,
                screenTimeSeconds = offset * 60,
                unlocks = offset,
                topApps = listOf(PhoneTopApp(if (offset == 2) "Selected App" else "Other App", 1.0)),
            )
        }
        val selected = mutableStateOf<String?>(null)
        var openedCheckIn = false
        compose.setContent {
            DiariumTheme {
                ScreenTimeCharts(
                    entries = sourceDays,
                    today = today,
                    startDate = "2026-01-01",
                    endDate = "2026-02-04",
                    selectedDate = selected.value,
                    onSelectDate = { selected.value = it },
                    onOpenDay = { openedCheckIn = true },
                )
            }
        }
        val day = "2026-01-03"
        compose.onAllNodesWithTag("screen_time_bucket_2026-01-01").assertCountEquals(1)
        compose.onNodeWithTag("screen_time_bucket_2026-01-01").performClick()
        compose.onAllNodesWithTag("screen_time_bucket_day_$day").assertCountEquals(1)
        compose.onNodeWithTag("screen_time_bucket_day_$day").performClick()
        compose.runOnIdle { assertEquals(day, selected.value) }
        // The selected source-day row is tagged independently of aggregate/window rows.
        compose.onAllNodesWithText("Selected App").assertCountEquals(2)
        compose.onNodeWithTag("selected_day_app_share_Selected App").assertTextEquals("50 %")
        compose.onAllNodesWithText("Other App").assertCountEquals(0)
        compose.onNodeWithTag("screen_time_bucket_day_$day").performClick()
        compose.runOnIdle {
            assertEquals(null, selected.value)
            assertEquals(false, openedCheckIn)
        }
        compose.onAllNodesWithText("Selected App").assertCountEquals(1)
        compose.onAllNodesWithText("Other App").assertCountEquals(1)
    }
}

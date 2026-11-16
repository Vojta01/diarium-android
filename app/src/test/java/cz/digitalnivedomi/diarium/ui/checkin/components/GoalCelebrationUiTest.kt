package cz.digitalnivedomi.diarium.ui.checkin.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import cz.digitalnivedomi.diarium.core.data.Goal
import cz.digitalnivedomi.diarium.core.data.GoalProgress
import cz.digitalnivedomi.diarium.ui.theme.DiariumTheme
import cz.digitalnivedomi.diarium.ui.theme.LocalReduceMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class GoalCelebrationUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `reduced motion monthly celebration includes reveal and exit in 1500ms and dismisses once`() =
        assertMonthlyLifecycle(reduceMotion = true)

    @Test
    fun `normal monthly celebration includes reveal and exit in 1500ms and dismisses once`() =
        assertMonthlyLifecycle(reduceMotion = false)

    private fun assertMonthlyLifecycle(reduceMotion: Boolean) {
        val goal = Goal(
            id = "monthly-goal",
            activityKey = "walk",
            name = "Walk",
            targetCount = 12,
            frequency = "monthly",
            isActive = true,
        )
        val progress = GoalProgress(goal = goal, count = 12, streak = 0)
        lateinit var dismissals: androidx.compose.runtime.MutableIntState
        compose.mainClock.autoAdvance = false

        compose.setContent {
            DiariumTheme {
                CompositionLocalProvider(LocalReduceMotion provides reduceMotion) {
                    val visible = remember { mutableStateOf(true) }
                    val count = remember { mutableIntStateOf(0) }
                    dismissals = count
                    if (visible.value) {
                        GoalCelebration(
                            goals = listOf(progress),
                            celebrationKey = "monthly-save-${if (reduceMotion) "reduced" else "normal"}",
                            onDone = {
                                count.intValue++
                                visible.value = false
                            },
                        )
                    }
                }
            }
        }
        compose.waitForIdle()

        val celebration = compose.onNodeWithTag("goal_celebration")
        celebration.assertIsDisplayed()
        compose.mainClock.advanceTimeBy(1_499L, ignoreFrameDuration = true)
        celebration.assertIsDisplayed()
        assertEquals(0, dismissals.intValue)

        // Compose dispatches the completion callback on the next display frame.
        compose.mainClock.advanceTimeBy(32L, ignoreFrameDuration = true)
        compose.waitForIdle()
        assertEquals(1, dismissals.intValue)
        // Dialog's platform window removal is asynchronous after the composable callback.
        compose.mainClock.advanceTimeBy(2_000L, ignoreFrameDuration = true)
        compose.waitForIdle()
        compose.onNodeWithTag("goal_celebration").assertDoesNotExist()
        assertEquals(1, dismissals.intValue)

        compose.mainClock.advanceTimeBy(500L, ignoreFrameDuration = true)
        compose.waitForIdle()
        assertEquals(1, dismissals.intValue)
    }

    @Test
    fun `compact celebration is nonmodal and leaves other controls actionable`() {
        val goal = Goal(
            id = "daily-goal",
            activityKey = "walk",
            name = "Walk",
            targetCount = 3,
            frequency = "daily",
            isActive = true,
        )
        val progress = GoalProgress(goal = goal, count = 3, streak = 0)
        lateinit var taps: androidx.compose.runtime.MutableIntState
        compose.mainClock.autoAdvance = false

        compose.setContent {
            DiariumTheme {
                val count = remember { mutableIntStateOf(0) }
                taps = count
                Column(Modifier.fillMaxSize()) {
                    GoalCelebration(
                        goals = listOf(progress),
                        celebrationKey = "save-1",
                        onDone = {},
                    )
                    Box(
                        Modifier
                            .padding(top = 180.dp)
                            .clickable { count.intValue++ }
                            .testTag("underlying_control"),
                    ) { Text("Continue editing") }
                }
            }
        }

        val notice = compose.onNodeWithTag("goal_celebration_compact")
        notice.assertIsDisplayed()
        assertFalse(notice.fetchSemanticsNode().config.contains(SemanticsActions.OnClick))

        compose.onNodeWithTag("underlying_control").performClick()
        assertEquals(1, taps.intValue)
    }
}

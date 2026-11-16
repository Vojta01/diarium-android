package cz.digitalnivedomi.diarium.ui.stats

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import cz.digitalnivedomi.diarium.ui.theme.DiariumTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class StatsCategorySelectorUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun `all statistics categories render and selection dispatches`() {
        var selected = StatsCategory.MOOD
        compose.setContent {
            DiariumTheme {
                StatsCategorySelector(category = selected, onSelect = { selected = it })
            }
        }
        compose.onNodeWithTag(StatsCategory.MOOD.testTag).assertIsSelected()
        compose.onNodeWithTag(StatsCategory.DIGITAL.testTag).performClick()
        compose.runOnIdle { assertEquals(StatsCategory.DIGITAL, selected) }
    }
}

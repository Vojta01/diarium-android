package cz.digitalnivedomi.diarium.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import cz.digitalnivedomi.diarium.ui.checkin.CheckInScreen
import cz.digitalnivedomi.diarium.ui.theme.DiariumTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Renders real Compose screens on the JVM (Robolectric), so UI regressions are
 * caught without a device in the loop.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class CheckInScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `check-in screen renders its heading`() {
        compose.setContent {
            DiariumTheme {
                CheckInScreen()
            }
        }
        compose.onNodeWithText("Dnešní zápis").assertExists()
    }
}

package cz.digitalnivedomi.diarium.ui.auth

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import cz.digitalnivedomi.diarium.ui.theme.DiariumTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The real login screen renders on the JVM (Robolectric) and drives sign-in. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class LoginScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `login screen shows the google button and the value proposition`() {
        compose.setContent {
            DiariumTheme {
                LoginScreen()
            }
        }

        compose.onNodeWithTag(GoogleSignInButtonTag).assertExists().assertIsEnabled()
        compose.onNodeWithText(GoogleSignInLabel).assertExists()
        compose.onNodeWithText("Diarium").assertExists()
        compose.onNodeWithText("Nálada, spánek, aktivity a poznámky").assertExists()
    }

    @Test
    fun `tapping the button starts the oauth flow`() {
        var clicks = 0
        compose.setContent {
            DiariumTheme {
                LoginScreen(onSignIn = { clicks++ })
            }
        }

        compose.onNodeWithTag(GoogleSignInButtonTag).performClick()

        assertEquals(1, clicks)
    }

    @Test
    fun `while the custom tab is open the button is disabled and busy`() {
        compose.setContent {
            DiariumTheme {
                LoginScreen(isSigningIn = true)
            }
        }

        compose.onNodeWithTag(GoogleSignInButtonTag).assertIsNotEnabled()
        compose.onNodeWithText(GoogleSignInBusyLabel).assertExists()
        compose.onNodeWithText(GoogleSignInLabel).assertDoesNotExist()
    }

    @Test
    fun `a failed callback shows an error message`() {
        compose.setContent {
            DiariumTheme {
                LoginScreen(errorMessage = "Přihlášení se nepovedlo. Zkus to prosím znovu.")
            }
        }

        compose.onNodeWithText("Přihlášení se nepovedlo. Zkus to prosím znovu.").assertExists()
    }
}

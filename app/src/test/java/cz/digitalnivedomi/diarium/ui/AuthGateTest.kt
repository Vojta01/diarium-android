package cz.digitalnivedomi.diarium.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cz.digitalnivedomi.diarium.auth.AuthManager
import cz.digitalnivedomi.diarium.auth.AuthStateHolder
import cz.digitalnivedomi.diarium.auth.AuthStatus
import cz.digitalnivedomi.diarium.auth.SessionStore
import cz.digitalnivedomi.diarium.ui.auth.GoogleSignInButtonTag
import cz.digitalnivedomi.diarium.ui.auth.GoogleSignInLabel
import cz.digitalnivedomi.diarium.ui.theme.DiariumTheme
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.Base64

/**
 * The session gate in the app shell: UNAUTHENTICATED shows the login screen with
 * no bottom bar, the OAuth deep link authenticates in place, and logout goes back.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class AuthGateTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val base64 = Base64.getUrlEncoder().withoutPadding()

    private fun session(): JSONObject = JSONObject().apply {
        put("access_token", "access-1")
        put("refresh_token", "refresh-1")
        put("expires_at", System.currentTimeMillis() / 1000 + 3600)
        put("expires_in", 3600)
        put("user", JSONObject().put("id", "user-1"))
    }

    /** A real `diarium://auth-callback#…` link with a decodable JWT. */
    private fun sessionUri(): String {
        val header = base64.encodeToString("""{"alg":"HS256","typ":"JWT"}""".toByteArray())
        val payload = base64.encodeToString("""{"sub":"user-1","email":"vojta@example.com"}""".toByteArray())
        return "diarium://auth-callback#access_token=$header.$payload.sig&refresh_token=r1&expires_in=3600"
    }

    @Test
    fun `signed out shows the login screen and no bottom bar`() {
        val holder = AuthStateHolder(SessionStore(context))
        compose.setContent {
            DiariumTheme {
                DiariumApp(authDeepLink = mutableStateOf<String?>(null), authState = holder)
            }
        }

        compose.onNodeWithText(GoogleSignInLabel).assertExists()
        compose.onNodeWithTag(GoogleSignInButtonTag).assertExists()
        // Bottom-bar tabs must not exist while signed out.
        compose.onNodeWithText("Historie").assertDoesNotExist()
        compose.onNodeWithText("Přehledy").assertDoesNotExist()
        assertEquals(AuthStatus.UNAUTHENTICATED, holder.status.value)
    }

    @Test
    fun `the oauth deep link authenticates in place and reveals the nav bar`() {
        val store = SessionStore(context)
        val holder = AuthStateHolder(store)
        val link = mutableStateOf<String?>(null)
        val handled = mutableListOf<String>()

        compose.setContent {
            DiariumTheme {
                DiariumApp(
                    authDeepLink = link,
                    authState = holder,
                    onAuthDeepLink = { raw ->
                        // Mirrors MainActivity → AuthManager.handleAuthCallback, minus
                        // the WorkManager/FCM side effects (no Activity in a JVM test).
                        handled += raw
                        val session = AuthManager.parseSessionFromCallback(raw)
                        if (session != null) {
                            store.save(session)
                            holder.refresh()
                            true
                        } else {
                            false
                        }
                    },
                )
            }
        }
        compose.onNodeWithText(GoogleSignInLabel).assertExists()

        compose.runOnIdle { link.value = sessionUri() }
        compose.waitForIdle()

        assertEquals(listOf(sessionUri()), handled)
        assertTrue(store.hasSession())
        assertEquals(AuthStatus.AUTHENTICATED, holder.status.value)
        // Same composition, no restart: bottom bar is here, login screen is gone.
        compose.onNodeWithText("Historie").assertExists()
        compose.onNodeWithText(GoogleSignInLabel).assertDoesNotExist()
    }

    @Test
    fun `an unusable deep link keeps the gate closed and reports an error`() {
        val store = SessionStore(context)
        val holder = AuthStateHolder(store)
        val link = mutableStateOf<String?>(null)

        compose.setContent {
            DiariumTheme {
                DiariumApp(
                    authDeepLink = link,
                    authState = holder,
                    onAuthDeepLink = { raw ->
                        val session = AuthManager.parseSessionFromCallback(raw)
                        if (session != null) {
                            store.save(session)
                            holder.refresh()
                            true
                        } else {
                            false
                        }
                    },
                )
            }
        }

        compose.runOnIdle { link.value = "diarium://auth-callback#error=access_denied" }
        compose.waitForIdle()

        assertEquals(AuthStatus.UNAUTHENTICATED, holder.status.value)
        compose.onNodeWithText("Přihlášení se nepovedlo. Zkus to prosím znovu.").assertExists()
        compose.onNodeWithText("Historie").assertDoesNotExist()
    }

    @Test
    fun `logout from settings returns to the login screen`() {
        val store = SessionStore(context)
        store.save(session())
        val holder = AuthStateHolder(store)
        compose.setContent {
            DiariumTheme {
                DiariumApp(authDeepLink = mutableStateOf<String?>(null), authState = holder)
            }
        }

        compose.onNodeWithText("Historie").assertExists() // authenticated shell
        compose.onNodeWithText("Nastavení").performClick()
        compose.waitForIdle()
        // The settings column scrolls: entries added above push the account card
        // below the fold, so scroll to the button before pressing it.
        compose.onNodeWithText("Odhlásit se").performScrollTo().performClick()
        compose.waitForIdle()

        assertEquals(AuthStatus.UNAUTHENTICATED, holder.status.value)
        compose.onNodeWithText(GoogleSignInLabel).assertExists()
        compose.onNodeWithText("Nastavení").assertDoesNotExist()
    }
}

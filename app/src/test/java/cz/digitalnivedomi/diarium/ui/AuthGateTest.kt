package cz.digitalnivedomi.diarium.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cz.digitalnivedomi.diarium.auth.AuthCallbackOutcome
import cz.digitalnivedomi.diarium.auth.AuthStateHolder
import cz.digitalnivedomi.diarium.auth.AuthStatus
import cz.digitalnivedomi.diarium.auth.SessionStore
import cz.digitalnivedomi.diarium.core.data.ProfileStore
import cz.digitalnivedomi.diarium.ui.auth.GoogleSignInButtonTag
import cz.digitalnivedomi.diarium.ui.auth.GoogleSignInLabel
import cz.digitalnivedomi.diarium.ui.theme.DiariumTheme
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        put("access_token", jwt("user-1"))
        put("refresh_token", "test-refresh")
        put("expires_at", System.currentTimeMillis() / 1000 + 3600)
        put("expires_in", 3600)
        put("token_type", "bearer")
        put("user", JSONObject().put("id", "user-1").put("email", "user@example.com"))
        put("verified_user_id", "user-1")
    }

    /** Synthetic callback; identity verification is exercised in AuthPkceFlowTest. */
    private fun sessionUri(): String = "diarium://auth-callback?state=state-1&code=fake-code"

    private fun jwt(subject: String): String {
        val header = base64.encodeToString("""{"alg":"HS256","typ":"JWT"}""".toByteArray())
        val payload = base64.encodeToString("""{"sub":"$subject"}""".toByteArray())
        return "$header.$payload.synthetic-signature"
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
        // The shell gate waits for the profile store; mark onboarding done so the
        // authenticated state renders the tabs, not the first-run wizard.
        runBlocking { ProfileStore.from(context, "user-1").setOnboardingDone(true) }
        val link = mutableStateOf<String?>(null)
        val handled = mutableListOf<String>()

        compose.setContent {
            DiariumTheme {
                DiariumApp(
                    authDeepLink = link,
                    authState = holder,
                    onAuthDeepLink = { raw ->
                        // This UI-only fake models a successful PKCE exchange; the
                        // fake-transport protocol tests cover code/state verification.
                        handled += raw
                        store.save(session())
                        holder.refresh()
                        AuthCallbackOutcome.Verified("user-1")
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
        // The shell appears once the profile store answers (seeded above).
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Historie").fetchSemanticsNodes().isNotEmpty() }
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
                    onAuthDeepLink = { AuthCallbackOutcome.Rejected },
                )
            }
        }

        compose.runOnIdle { link.value = "diarium://auth-callback?state=invalid&code=fake-code" }
        compose.waitForIdle()

        assertEquals(AuthStatus.UNAUTHENTICATED, holder.status.value)
        compose.onNodeWithText("Přihlášení se nepodařilo ověřit. Zkontroluj připojení a zkus to znovu.").assertExists()
        compose.onNodeWithText("Historie").assertDoesNotExist()
    }

    @Test
    fun `failed verification keeps the existing owner session and reports an error`() {
        val store = SessionStore(context)
        store.save(session())
        val originalSession = store.sessionJson()
        val holder = AuthStateHolder(store)
        runBlocking { ProfileStore.from(context, "user-1").setOnboardingDone(true) }
        val link = mutableStateOf<String?>(null)

        compose.setContent {
            DiariumTheme {
                DiariumApp(
                    authDeepLink = link,
                    authState = holder,
                    onAuthDeepLink = { AuthCallbackOutcome.Rejected },
                )
            }
        }
        compose.runOnIdle { link.value = sessionUri() }
        compose.waitForIdle()

        assertEquals(originalSession, store.sessionJson())
        assertEquals(AuthStatus.AUTHENTICATED, holder.status.value)
        assertEquals("user-1", holder.ownerId.value)
        compose.onNodeWithText("Ověření přihlášení").assertExists()
        compose.onNodeWithText("Historie").assertExists()
    }

    @Test
    fun `ownerless stored session is gated and can be cleared from login`() {
        val store = SessionStore(context).apply {
            save(
                JSONObject()
                    .put("access_token", "opaque-token-must-not-be-shown")
                    .put("refresh_token", "refresh-legacy")
                    .put("user", JSONObject().put("id", "")),
            )
        }
        val holder = AuthStateHolder(store)
        compose.setContent {
            DiariumTheme {
                DiariumApp(authDeepLink = mutableStateOf<String?>(null), authState = holder)
            }
        }

        assertEquals(AuthStatus.UNAUTHENTICATED, holder.status.value)
        assertTrue(holder.hasUnusableStoredSession)
        compose.onNodeWithText("Vymazat uložené přihlášení").assertExists().performClick()
        compose.waitForIdle()

        assertFalse(store.hasSession())
        assertFalse(holder.hasUnusableStoredSession)
        assertEquals(AuthStatus.UNAUTHENTICATED, holder.status.value)
        compose.onNodeWithText("opaque-token-must-not-be-shown").assertDoesNotExist()
    }

    @Test
    fun `logout from settings returns to the login screen`() {
        val store = SessionStore(context)
        store.save(session())
        // The shell gate waits for the profile store; mark onboarding done so the
        // authenticated state renders the tabs, not the first-run wizard.
        runBlocking { ProfileStore.from(context, "user-1").setOnboardingDone(true) }
        val holder = AuthStateHolder(store)
        compose.setContent {
            DiariumTheme {
                DiariumApp(authDeepLink = mutableStateOf<String?>(null), authState = holder)
            }
        }

        // The shell appears once the profile store answers (seeded above).
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Historie").fetchSemanticsNodes().isNotEmpty() }
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

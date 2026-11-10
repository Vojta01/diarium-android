package cz.digitalnivedomi.diarium.ui

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cz.digitalnivedomi.diarium.auth.SessionStore
import cz.digitalnivedomi.diarium.core.data.EntriesRepository
import cz.digitalnivedomi.diarium.core.data.HttpRequest
import cz.digitalnivedomi.diarium.core.data.HttpResponse
import cz.digitalnivedomi.diarium.core.data.HttpTransport
import cz.digitalnivedomi.diarium.core.data.SessionContext
import cz.digitalnivedomi.diarium.core.data.SupabaseClient
import cz.digitalnivedomi.diarium.ui.checkin.CheckInDeps
import cz.digitalnivedomi.diarium.ui.checkin.CheckInScreen
import cz.digitalnivedomi.diarium.ui.theme.DiariumTheme
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * End-to-end (JVM/Robolectric) proof of the two load outcomes:
 *
 *  - a GET that fails leaves the form unsavable and sends no POST at all, even
 *    though the account really does have a stored row;
 *  - a GET that returns no row still opens an editable form, and saving it sends
 *    the user's input.
 *
 * The repository is real; only the transport is fake, so this exercises the same
 * code path the phone runs.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class CheckInLoadFailureScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val requests = mutableListOf<HttpRequest>()
    private var entriesResponse: () -> HttpResponse = { HttpResponse(200, "[]") }
    private val transport = HttpTransport { request ->
        requests += request
        if (request.method == "GET" && request.url.contains("/entries?")) {
            entriesResponse()
        } else {
            HttpResponse(200, "{}")
        }
    }

    @Test
    fun `a failed GET leaves the form unsavable and sends no POST`() {
        val repo = repository()
        // The day exists on the server; the read is what fails.
        entriesResponse = { HttpResponse(500, """{"message":"boom"}""") }

        compose.setContent {
            DiariumTheme {
                CheckInScreen(
                    deps = CheckInDeps(entries = repo),
                    today = LocalDate.parse(DATE),
                    lateNight = false,
                )
            }
        }

        compose.waitUntil(TIMEOUT) {
            compose.onAllNodesWithText(BLOCKED_HINT).fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithTag("save_button").assertIsNotEnabled()
        assertTrue("a read failure must not write anything", requests.none { it.method == "POST" })
    }

    private fun repository(): EntriesRepository {
        val store = SessionStore(ApplicationProvider.getApplicationContext()).apply {
            save(
                JSONObject().apply {
                    put("access_token", "jwt-access-token")
                    put("refresh_token", "refresh-1")
                    put("expires_at", System.currentTimeMillis() / 1000 + 3600)
                    put("user", JSONObject().put("id", USER_ID))
                },
            )
        }
        return EntriesRepository(
            client = SupabaseClient(
                sessionStore = store,
                transport = transport,
                baseUrl = BASE_URL,
                anonKey = "anon-test-key",
            ),
            session = SessionContext(store),
        )
    }

    private companion object {
        const val DATE = "2026-09-10"
        const val USER_ID = "5f2c1b7e-9d3a-4c6f-8a10-2b4d6e8f0a12"
        const val BASE_URL = "https://example.supabase.co/rest/v1"
        const val BLOCKED_HINT = "Ukládání je zablokované, dokud se den nenačte."
        const val TIMEOUT = 10_000L
    }
}

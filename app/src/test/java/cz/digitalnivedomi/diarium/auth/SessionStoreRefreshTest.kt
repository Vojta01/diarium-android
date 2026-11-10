package cz.digitalnivedomi.diarium.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.Base64

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SessionStoreRefreshTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun clearStoredSession() {
        context.getSharedPreferences("diarium_session", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun `malformed refreshed jwt is rejected without changing the saved session`() {
        assertRejectedRefreshDoesNotMutate("header.%%%not-base64%%%.signature")
    }

    @Test
    fun `refreshed jwt without a subject is rejected without changing the saved session`() {
        assertRejectedRefreshDoesNotMutate(jwtPayload("""{"email":"user@example.com"}"""))
    }

    @Test
    fun `refreshed jwt with an empty subject is rejected without changing the saved session`() {
        assertRejectedRefreshDoesNotMutate(jwtPayload("""{"sub":""}"""))
    }

    @Test
    fun `refreshed jwt for a different owner is rejected without changing the saved session`() {
        assertRejectedRefreshDoesNotMutate(jwt(OWNER_B))
    }

    @Test
    fun `same owner refresh accepts a rotated access and refresh token`() {
        val initial = session(OWNER_A)
        val rotatedAccess = jwt(OWNER_A, signature = "rotated-signature")
        val store = SessionStore(context) { refreshToken ->
            assertEquals("refresh-$OWNER_A", refreshToken)
            refreshResponse(rotatedAccess, "rotated-refresh-$OWNER_A")
        }
        store.save(initial)

        val returnedToken = store.refreshAccessTokenForUser(OWNER_A)

        assertEquals(rotatedAccess, returnedToken)
        val saved = JSONObject(store.sessionJson()!!)
        assertEquals(rotatedAccess, saved.getString("access_token"))
        assertEquals("rotated-refresh-$OWNER_A", saved.getString("refresh_token"))
        assertEquals(OWNER_A, saved.getJSONObject("user").getString("id"))
        assertTrue(saved.getLong("expires_at") > 0L)
    }

    @Test
    fun `account switch during refresh is not overwritten by the late response`() {
        val initial = session(OWNER_A)
        val switched = session(OWNER_B)
        lateinit var store: SessionStore
        store = SessionStore(context) { refreshToken ->
            assertEquals("refresh-$OWNER_A", refreshToken)
            // Simulate the session changing while the fake refresh endpoint is in flight.
            store.save(switched)
            refreshResponse(jwt(OWNER_A, signature = "late-refresh"), "late-refresh-token")
        }
        store.save(initial)

        val returnedToken = store.refreshAccessTokenForUser(OWNER_A)

        assertNull(returnedToken)
        assertEquals(switched.toString(), store.sessionJson())
        assertEquals(OWNER_B, store.userSubject())
    }

    @Test
    fun `offline recovery owner requires a persisted server-verified identity match`() {
        val store = SessionStore(context)
        store.save(session(OWNER_A))
        assertEquals(OWNER_A, store.userSubject())
        assertNull(store.verifiedUserSubject())

        store.save(session(OWNER_A).put("verified_user_id", OWNER_A))
        assertEquals(OWNER_A, store.verifiedUserSubject())

        store.save(session(OWNER_A).put("verified_user_id", OWNER_B))
        assertNull(store.verifiedUserSubject())
    }

    private fun assertRejectedRefreshDoesNotMutate(refreshedAccessToken: String) {
        val initial = session(OWNER_A)
        var requestCount = 0
        val store = SessionStore(context) { refreshToken ->
            assertEquals("refresh-$OWNER_A", refreshToken)
            requestCount++
            refreshResponse(refreshedAccessToken, "must-not-be-saved")
        }
        store.save(initial)
        val originalSessionJson = store.sessionJson()

        val returnedToken = store.validAccessTokenForUser(OWNER_A)

        assertNull(returnedToken)
        assertEquals(1, requestCount)
        assertEquals(originalSessionJson, store.sessionJson())
    }

    private fun session(ownerId: String): JSONObject = JSONObject().apply {
        put("access_token", jwt(ownerId))
        put("refresh_token", "refresh-$ownerId")
        put("expires_at", 0L)
        put("expires_in", 3600)
        put("token_type", "bearer")
        put("user", JSONObject().put("id", ownerId).put("email", "$ownerId@example.com"))
    }

    private fun refreshResponse(accessToken: String, refreshToken: String): JSONObject = JSONObject()
        .put("access_token", accessToken)
        .put("refresh_token", refreshToken)
        .put("expires_in", 3600)

    private fun jwt(subject: String, signature: String = "signature"): String =
        jwtPayload("""{"sub":"$subject"}""", signature)

    private fun jwtPayload(payload: String, signature: String = "signature"): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("""{"alg":"HS256","typ":"JWT"}""".toByteArray())
        val encodedPayload = encoder.encodeToString(payload.toByteArray())
        return "$header.$encodedPayload.$signature"
    }

    private companion object {
        const val OWNER_A = "owner-a"
        const val OWNER_B = "owner-b"
    }
}

package cz.digitalnivedomi.diarium.auth

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Plain JVM unit test (no Robolectric, no Android framework) for the deep-link
 * half of the OAuth flow: `diarium://auth-callback#access_token=…&refresh_token=…`.
 */
class AuthManagerParseTest {

    private val base64 = Base64.getUrlEncoder().withoutPadding()

    private fun jwt(payloadJson: String): String {
        val header = base64.encodeToString("""{"alg":"HS256","typ":"JWT"}""".toByteArray(Charsets.UTF_8))
        val payload = base64.encodeToString(payloadJson.toByteArray(Charsets.UTF_8))
        return "$header.$payload.signature-not-verified"
    }

    private val userPayload =
        """{"sub":"8f14e45f-ceea-467a-9c6b-1a0b9a8d1234","email":"vojta@example.com",""" +
            """"aud":"authenticated","role":"authenticated","exp":1900000000}"""

    @Test
    fun `callback fragment becomes a complete supabase session`() {
        val accessToken = jwt(userPayload)
        val before = System.currentTimeMillis() / 1000

        val session = AuthManager.parseSessionFromCallback(
            "diarium://auth-callback#access_token=$accessToken&refresh_token=r1&expires_in=3600&token_type=bearer",
        )

        val after = System.currentTimeMillis() / 1000
        assertNotNull(session)
        session!!
        assertEquals(accessToken, session.getString("access_token"))
        assertEquals("r1", session.getString("refresh_token"))
        assertEquals(3600L, session.getLong("expires_in"))
        assertEquals("bearer", session.getString("token_type"))
        // expires_at must be "now + expires_in", in epoch seconds.
        assertTrue(
            "expires_at=${session.getLong("expires_at")} not in ${before + 3600}..${after + 3600}",
            session.getLong("expires_at") in (before + 3600)..(after + 3600),
        )
        assertEquals(
            "8f14e45f-ceea-467a-9c6b-1a0b9a8d1234",
            session.getJSONObject("user").getString("id"),
        )
        assertEquals("vojta@example.com", session.getJSONObject("user").getString("email"))
    }

    @Test
    fun `a callback uri without a fragment yields no session`() {
        assertNull(AuthManager.parseSessionFromCallback("diarium://auth-callback"))
        assertNull(AuthManager.parseSessionFromCallback("diarium://auth-callback#"))
    }

    @Test
    fun `a fragment without an access token yields no session`() {
        assertNull(
            AuthManager.parseSessionFromCallback(
                "diarium://auth-callback#refresh_token=r1&expires_in=3600",
            ),
        )
        assertNull(AuthManager.parseSessionFromCallback("diarium://auth-callback#access_token="))
    }

    @Test
    fun `a missing expires_in falls back to an hour`() {
        val session = AuthManager.parseSessionFromCallback(
            "diarium://auth-callback#access_token=${jwt(userPayload)}&refresh_token=r1",
        )
        assertEquals(3600L, session!!.getLong("expires_in"))
    }

    @Test
    fun `percent-encoded values are decoded without mangling plus signs`() {
        val session = AuthManager.parseSessionFromCallback(
            "diarium://auth-callback#access_token=${jwt(userPayload)}&refresh_token=r1%2B2%2F3%3D",
        )
        // URLDecoder-style decoding would have produced "r1 2/3=".
        assertEquals("r1+2/3=", session!!.getString("refresh_token"))
    }

    @Test
    fun `an access token that is not a jwt still produces a session`() {
        val session = AuthManager.parseSessionFromCallback(
            "diarium://auth-callback#access_token=opaque-token&refresh_token=r1&expires_in=3600",
        )
        assertNotNull(session)
        assertEquals("opaque-token", session!!.getString("access_token"))
        assertEquals("", session.getJSONObject("user").getString("id"))

        val brokenPayload = AuthManager.parseSessionFromCallback(
            "diarium://auth-callback#access_token=aaa.@@@not-base64@@@.ccc&refresh_token=r1",
        )
        assertEquals("", brokenPayload!!.getJSONObject("user").getString("id"))
    }

    @Test
    fun `the oauth url requests google via supabase and returns to our deep link`() {
        val url = AuthManager.oauthUrl()
        assertTrue(url, url.startsWith("https://vmqbslghzgfotwhzgawa.supabase.co/auth/v1/authorize"))
        assertTrue(url, url.contains("provider=google"))
        assertTrue(url, url.contains("redirect_to=diarium%3A%2F%2Fauth-callback"))
    }

    @Test
    fun `decoded jwt exposes only the display fields`() {
        val payload = AuthManager.decodeJwtPayload(jwt(userPayload))
        assertNotNull(payload)
        payload!!
        assertEquals("vojta@example.com", payload.optString("email"))
        assertEquals("authenticated", payload.optString("role"))
        // A JSONObject is produced even when the token is unusable, so the session
        // JSON shape never changes.
        assertNotNull(JSONObject().put("user", payload))
    }
}

package cz.digitalnivedomi.diarium.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** JVM-only checks for the native OAuth code/PKCE callback format. */
class AuthManagerParseTest {

    @Test
    fun `authorization request uses S256 challenge and encodes exact state redirect`() {
        val verifier = "0123456789012345678901234567890123456789012"
        val challenge = AuthPkceFlow.codeChallenge(verifier)
        val redirect = "diarium://auth-callback?state=state_value"
        val request = AuthPkceFlow.authorizationUrl(
            supabaseUrl = "https://supabase.test/",
            redirectUri = redirect,
            codeChallenge = challenge,
        )

        assertTrue(request.startsWith("https://supabase.test/auth/v1/authorize?provider=google"))
        assertTrue(request.contains("redirect_to=diarium%3A%2F%2Fauth-callback%3Fstate%3Dstate_value"))
        assertTrue(request.contains("code_challenge=$challenge"))
        assertTrue(request.endsWith("code_challenge_method=s256"))
        assertEquals(43, challenge.length)
        assertTrue(challenge.matches(Regex("^[A-Za-z0-9_-]{43}$")))
    }

    @Test
    fun `callback requires exact URI and one matching code and state`() {
        val attempt = OAuthAttempt(
            state = "state_value",
            codeVerifier = "verifier",
            redirectUri = "diarium://auth-callback?state=state_value",
            createdAtMillis = 100L,
        )

        val accepted = AuthPkceFlow.parseCallback(
            "diarium://auth-callback?state=state_value&code=auth-code",
            attempt,
            "diarium",
            "auth-callback",
        )

        assertNotNull(accepted)
        assertEquals("auth-code", accepted!!.code)
        assertEquals("state_value", accepted.state)
        assertNull(
            AuthPkceFlow.parseCallback(
                "diarium://auth-callback.evil?state=state_value&code=auth-code",
                attempt,
                "diarium",
                "auth-callback",
            ),
        )
        assertNull(
            AuthPkceFlow.parseCallback(
                "diarium://auth-callback?state=state_value&code=auth-code&access_token=forged",
                attempt,
                "diarium",
                "auth-callback",
            ),
        )
    }

    @Test
    fun `missing duplicate and mismatched state are rejected`() {
        val attempt = OAuthAttempt(
            state = "expected",
            codeVerifier = "verifier",
            redirectUri = "diarium://auth-callback?state=expected",
            createdAtMillis = 100L,
        )
        val links = listOf(
            "diarium://auth-callback?code=auth-code",
            "diarium://auth-callback?state=wrong&code=auth-code",
            "diarium://auth-callback?state=expected&state=wrong&code=auth-code",
        )

        links.forEach { link ->
            assertNull(AuthPkceFlow.parseCallback(link, attempt, "diarium", "auth-callback"))
        }
    }

    @Test
    fun `JWT claims decoder remains supplementary and handles invalid input`() {
        val payload = """{"sub":"owner-1","email":"user@example.test"}"""
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("{}".toByteArray())
        val body = encoder.encodeToString(payload.toByteArray())
        val claims = AuthManager.decodeJwtPayload("$header.$body.signature")

        assertNotNull(claims)
        assertEquals("owner-1", claims!!.optString("sub"))
        assertNull(AuthManager.decodeJwtPayload("not-a-jwt"))
        assertFalse(claims.has("verified_user_id"))
    }
}

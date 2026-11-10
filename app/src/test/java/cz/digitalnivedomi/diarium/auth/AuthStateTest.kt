package cz.digitalnivedomi.diarium.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.Base64

/**
 * The session gate: [AuthStateHolder] must derive AUTHENTICATED / UNAUTHENTICATED
 * from the persisted [SessionStore], including offline legacy sessions, and
 * publish verified callback commits or logout without an in-memory-only state.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AuthStateTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun accessToken(userId: String): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("""{"alg":"HS256","typ":"JWT"}""".toByteArray())
        val payload = encoder.encodeToString("""{"sub":"$userId"}""".toByteArray())
        return "$header.$payload.test-signature"
    }

    private fun session(userId: String = "user-1"): JSONObject = JSONObject().apply {
        put("access_token", accessToken(userId))
        put("refresh_token", "refresh-1")
        put("expires_at", System.currentTimeMillis() / 1000 + 3600)
        put("expires_in", 3600)
        put("token_type", "bearer")
        put("user", JSONObject().put("id", userId).put("email", "user@example.com"))
    }

    private fun verifiedSession(userId: String = "user-1"): JSONObject =
        session(userId).put("verified_user_id", userId)

    @Test
    fun `gate starts unauthenticated when nothing is stored`() {
        val store = SessionStore(context)
        assertFalse(store.hasSession())
        assertEquals(AuthStatus.UNAUTHENTICATED, AuthStateHolder(store).status.value)
    }

    @Test
    fun `gate refreshes after a server verified callback commit`() {
        val store = SessionStore(context)
        val holder = AuthStateHolder(store)
        assertEquals(AuthStatus.UNAUTHENTICATED, holder.status.value)

        // AuthCallbackSessionVerifier writes the server-returned owner id before
        // AuthManager asks the holder to publish the newly accepted session.
        store.save(verifiedSession())
        assertTrue(store.hasSession())
        holder.refresh()

        assertEquals(AuthStatus.AUTHENTICATED, holder.status.value)
        assertTrue(holder.isAuthenticated)
    }

    @Test
    fun `account switch updates owner while gate stays authenticated`() {
        val store = SessionStore(context).apply { save(session(userId = "account-a")) }
        val holder = AuthStateHolder(store)
        assertEquals("account-a", holder.ownerId.value)

        store.save(verifiedSession(userId = "account-b"))
        holder.refresh()

        assertEquals(AuthStatus.AUTHENTICATED, holder.status.value)
        assertEquals("account-b", holder.ownerId.value)
    }

    @Test
    fun `a stored session authenticates a brand new holder - the restart case`() {
        val store = SessionStore(context)
        store.save(session())

        // A new holder = a new process: no in-memory state is carried over.
        val restarted = AuthStateHolder(SessionStore(context))

        assertEquals(AuthStatus.AUTHENTICATED, restarted.status.value)
    }

    @Test
    fun `signOut clears the stored session and flips back to unauthenticated`() {
        val store = SessionStore(context)
        store.save(session())
        val holder = AuthStateHolder(store)
        assertEquals(AuthStatus.AUTHENTICATED, holder.status.value)

        holder.signOut()

        assertEquals(AuthStatus.UNAUTHENTICATED, holder.status.value)
        assertNull(holder.ownerId.value)
        assertFalse(store.hasSession())
        assertNull(store.accessToken())
        assertNull(store.validAccessToken())
    }

    @Test
    fun `ownerless stored session is gated out and can be recovered or signed out`() {
        val store = SessionStore(context).apply {
            save(
                JSONObject()
                    .put("access_token", "opaque-token")
                    .put("refresh_token", "refresh-legacy")
                    .put("user", JSONObject().put("id", "")),
            )
        }
        val holder = AuthStateHolder(store)

        assertEquals(AuthStatus.UNAUTHENTICATED, holder.status.value)
        assertNull(holder.ownerId.value)

        store.save(verifiedSession())
        holder.refresh()
        assertEquals(AuthStatus.AUTHENTICATED, holder.status.value)
        assertEquals("user-1", holder.ownerId.value)

        holder.signOut()
        assertEquals(AuthStatus.UNAUTHENTICATED, holder.status.value)
        assertFalse(store.hasSession())
    }
}

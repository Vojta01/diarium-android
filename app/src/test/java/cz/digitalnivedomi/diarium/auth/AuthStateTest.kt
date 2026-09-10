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

/**
 * The session gate: [AuthStateHolder] must derive AUTHENTICATED / UNAUTHENTICATED
 * from the persisted [SessionStore], and flip as soon as the OAuth callback saves
 * a session (the deep link path) or logout clears it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AuthStateTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun session(accessToken: String = "access-1"): JSONObject = JSONObject().apply {
        put("access_token", accessToken)
        put("refresh_token", "refresh-1")
        put("expires_at", System.currentTimeMillis() / 1000 + 3600)
        put("expires_in", 3600)
        put("token_type", "bearer")
        put("user", JSONObject().put("id", "user-1").put("email", "vojta@example.com"))
    }

    @Test
    fun `gate starts unauthenticated when nothing is stored`() {
        val store = SessionStore(context)
        assertFalse(store.hasSession())
        assertEquals(AuthStatus.UNAUTHENTICATED, AuthStateHolder(store).status.value)
    }

    @Test
    fun `gate flips from hasSession false to true after the callback saves a session`() {
        val store = SessionStore(context)
        val holder = AuthStateHolder(store)
        assertEquals(AuthStatus.UNAUTHENTICATED, holder.status.value)

        // This is exactly what AuthManager.handleAuthCallback does with the parsed
        // session; the holder is backed by the store, not by an in-memory flag.
        store.save(session())
        assertTrue(store.hasSession())
        holder.refresh()

        assertEquals(AuthStatus.AUTHENTICATED, holder.status.value)
        assertTrue(holder.isAuthenticated)
    }

    @Test
    fun `onSessionSaved persists and authenticates in one step`() {
        val store = SessionStore(context)
        val holder = AuthStateHolder(store)

        holder.onSessionSaved(session("access-2"))

        assertEquals(AuthStatus.AUTHENTICATED, holder.status.value)
        assertTrue(store.hasSession())
        assertEquals("access-2", store.accessToken())
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
        assertFalse(store.hasSession())
        assertNull(store.accessToken())
        assertNull(store.validAccessToken())
    }
}

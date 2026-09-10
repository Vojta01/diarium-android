package cz.digitalnivedomi.diarium.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** Whether the app currently has a usable Supabase session. */
enum class AuthStatus { AUTHENTICATED, UNAUTHENTICATED }

/**
 * Single source of truth for the session gate.
 *
 * The state is derived from [SessionStore.hasSession] — i.e. the persisted
 * SharedPreferences session, not an in-memory flag — so it survives process
 * death for free: a cold start with a stored session comes up AUTHENTICATED.
 *
 * The deep link path is: `MainActivity` → `AuthManager.handleAuthCallback`
 * (parses + persists + triggers background work) → [refresh] here → Compose
 * swaps the gate. No restart involved.
 */
class AuthStateHolder(private val sessionStore: SessionStore) {

    private val _status = MutableStateFlow(
        if (sessionStore.hasSession()) AuthStatus.AUTHENTICATED else AuthStatus.UNAUTHENTICATED,
    )

    /** Observed by the Compose gate. */
    val status: StateFlow<AuthStatus> = _status.asStateFlow()

    val isAuthenticated: Boolean get() = _status.value == AuthStatus.AUTHENTICATED

    /** Re-reads the persisted session and republishes the gate state. */
    fun refresh() {
        _status.value =
            if (sessionStore.hasSession()) AuthStatus.AUTHENTICATED else AuthStatus.UNAUTHENTICATED
    }

    /** Persists a freshly parsed OAuth session and flips the gate to AUTHENTICATED. */
    fun onSessionSaved(session: JSONObject) {
        sessionStore.save(session)
        refresh()
    }

    /** Logout: drops the stored tokens and flips the gate back to UNAUTHENTICATED. */
    fun signOut() {
        sessionStore.clear()
        refresh()
    }
}

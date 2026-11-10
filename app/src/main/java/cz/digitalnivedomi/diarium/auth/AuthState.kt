package cz.digitalnivedomi.diarium.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Whether the app currently has a usable Supabase session. */
enum class AuthStatus { AUTHENTICATED, UNAUTHENTICATED }

/**
 * Single source of truth for the session gate.
 *
 * The state is derived from the persisted [SessionStore]. Newly accepted callback
 * sessions carry the owner id returned by Supabase Auth; pre-existing sessions
 * retain their local owner pin for offline use. Ownerless legacy records stay gated
 * out but remain untouched for manual recovery.
 *
 * The deep link path is: `MainActivity` → `AuthManager.handleAuthCallback`
 * (parses + persists + triggers background work) → [refresh] here → Compose
 * swaps the gate. No restart involved.
 */
class AuthStateHolder(
    private val sessionStore: SessionStore,
    private val onSessionCleared: (() -> Unit)? = null,
) {

    private fun currentOwnerId(): String? = sessionStore.userSubject()

    private val initialOwnerId = currentOwnerId()
    private val _status = MutableStateFlow(
        if (initialOwnerId != null) AuthStatus.AUTHENTICATED else AuthStatus.UNAUTHENTICATED,
    )

    /** Observed by the Compose gate. */
    val status: StateFlow<AuthStatus> = _status.asStateFlow()

    private val _ownerId = MutableStateFlow(initialOwnerId)

    /** Current session owner; changes on an account replacement even if the gate stays authenticated. */
    val ownerId: StateFlow<String?> = _ownerId.asStateFlow()

    val isAuthenticated: Boolean get() = _status.value == AuthStatus.AUTHENTICATED

    /** The login screen offers clearing when a legacy/bad session cannot identify an owner. */
    val hasUnusableStoredSession: Boolean get() = sessionStore.hasSession() && !isAuthenticated

    /** Re-reads the persisted session after AuthManager commits a verified callback. */
    fun refresh() {
        val owner = currentOwnerId()
        _ownerId.value = owner
        _status.value = if (owner != null) AuthStatus.AUTHENTICATED else AuthStatus.UNAUTHENTICATED
    }

    /** Logout: drops the stored tokens and re-arms schedules for the now-signed-out device. */
    fun signOut() {
        sessionStore.clear()
        refresh()
        onSessionCleared?.invoke()
    }
}

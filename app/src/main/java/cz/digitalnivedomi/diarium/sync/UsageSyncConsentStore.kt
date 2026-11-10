package cz.digitalnivedomi.diarium.sync

import android.content.Context
import android.util.Base64
import cz.digitalnivedomi.diarium.core.data.UsageSyncRequestStore

/**
 * Screen-time sync is an account-scoped consent, not a device-wide preference.
 * The legacy `diarium_notifications/sync_enabled` bit has no owner, so its `true`
 * value cannot safely authorize any account. Migration records that ambiguity as
 * opt-out and deliberately leaves the old preference untouched.
 */
class UsageSyncConsentStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val legacyPrefs = appContext.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)

    /** Consent defaults to false and is never inferred from the live session. */
    fun isEnabledFor(ownerId: String?): Boolean {
        val owner = ownerId?.takeIf { it.isNotBlank() } ?: return false
        synchronized(LOCK) {
            val key = ownerKey(owner)
            if (prefs.contains(key)) return prefs.getBoolean(key, false)
            migrateLegacyFailClosed(key)
            return prefs.getBoolean(key, false)
        }
    }

    /** Store the user's explicit choice for one verified account only. */
    fun setEnabledFor(ownerId: String?, enabled: Boolean) {
        val owner = ownerId?.takeIf { it.isNotBlank() } ?: return
        synchronized(ownerDispatchLock(owner)) {
            synchronized(LOCK) {
                prefs.edit()
                    .putBoolean(ownerKey(owner), enabled)
                    .putBoolean(KEY_LEGACY_MIGRATION_COMPLETE, true)
                    .apply()
            }
            if (!enabled) {
                // An opted-out account must not retain queued usage snapshots for a
                // later process restart or silent replay after re-enabling.
                UsageSyncRequestStore(appContext).clearOwner(owner)
                // A backfill marker is written when work is queued. If opt-out
                // cancels that work, do not let a quick re-opt-in inherit it.
                SyncScheduler.resetBackfillState(appContext, owner)
            }
        }
    }

    /**
     * Serialize a quick network handoff with consent changes for this owner. The
     * block must enqueue/start the request and return without waiting for its result;
     * a request already handed to the transport cannot be recalled by opt-out.
     */
    internal fun <T> withOwnerDispatchLock(ownerId: String, block: () -> T): T {
        require(ownerId.isNotBlank()) { "Owner id must not be blank" }
        return synchronized(ownerDispatchLock(ownerId), block)
    }

    private fun ownerDispatchLock(ownerId: String): Any = synchronized(OWNER_LOCKS) {
        OWNER_LOCKS.getOrPut(ownerId) { Any() }
    }

    private fun migrateLegacyFailClosed(ownerKey: String) {
        if (prefs.getBoolean(KEY_LEGACY_MIGRATION_COMPLETE, false)) return

        // The old bit was shared by every account on the device (and could also
        // be written by unrelated notification-setting saves). Without a recorded
        // owner, even `true` is ambiguous and is not valid consent for this user.
        // Preserve the source preference; a user can explicitly opt in per account.
        val legacyWasSet = legacyPrefs.contains(KEY_LEGACY_SYNC_ENABLED)
        val legacyWasEnabled = legacyWasSet && legacyPrefs.getBoolean(KEY_LEGACY_SYNC_ENABLED, false)
        prefs.edit()
            .putBoolean(ownerKey, false)
            .putBoolean(KEY_LEGACY_MIGRATION_COMPLETE, true)
            .putBoolean(KEY_LEGACY_VALUE_WAS_ENABLED, legacyWasEnabled)
            .apply()
    }

    private fun ownerKey(ownerId: String): String =
        "$KEY_OWNER_PREFIX${Base64.encodeToString(ownerId.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)}"

    companion object {
        const val PREFS_NAME = "diarium_screen_sync_consent"
        private const val LEGACY_PREFS_NAME = "diarium_notifications"
        private const val LEGACY_SYNC_KEY = "sync_enabled"
        private const val KEY_OWNER_PREFIX = "owner."
        private const val KEY_LEGACY_MIGRATION_COMPLETE = "legacy_device_consent_migration_complete"
        private const val KEY_LEGACY_VALUE_WAS_ENABLED = "legacy_device_consent_was_enabled"
        private const val KEY_LEGACY_SYNC_ENABLED = LEGACY_SYNC_KEY
        private val LOCK = Any()
        private val OWNER_LOCKS = mutableMapOf<String, Any>()
    }
}

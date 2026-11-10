package cz.digitalnivedomi.diarium.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Per-account consent survives restarts without inheriting the legacy device toggle. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class UsageSyncConsentStoreTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(UsageSyncConsentStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences("diarium_sync", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun `device global legacy opt in fails closed when it has no owner and is preserved`() {
        val legacy = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        legacy.edit().putBoolean(LEGACY_SYNC_ENABLED, true).commit()
        val consent = UsageSyncConsentStore(context)

        assertFalse("ownerless device consent must not be assigned to the current account", consent.isEnabledFor(OWNER_A))
        assertFalse("the legacy grant must not leak to another account", consent.isEnabledFor(OWNER_B))
        assertTrue("migration preserves old preferences for user recovery", legacy.contains(LEGACY_SYNC_ENABLED))
        assertTrue(legacy.getBoolean(LEGACY_SYNC_ENABLED, false))
    }

    @Test
    fun `explicit consent belongs only to its owner and survives a new store instance`() {
        val consent = UsageSyncConsentStore(context)
        consent.setEnabledFor(OWNER_A, true)

        assertTrue(consent.isEnabledFor(OWNER_A))
        assertFalse(consent.isEnabledFor(OWNER_B))
        assertTrue(UsageSyncConsentStore(context).isEnabledFor(OWNER_A))
        assertFalse(UsageSyncConsentStore(context).isEnabledFor(OWNER_B))
    }

    @Test
    fun `opt out resets only that owner's backfill marker and repeated opt in does not reset others`() {
        val consent = UsageSyncConsentStore(context)
        consent.setEnabledFor(OWNER_A, true)
        consent.setEnabledFor(OWNER_B, true)
        SyncScheduler.markBackfillScheduled(context, OWNER_A)
        SyncScheduler.markBackfillScheduled(context, OWNER_B)
        assertFalse(SyncScheduler.isBackfillDue(context, OWNER_A))
        assertFalse(SyncScheduler.isBackfillDue(context, OWNER_B))

        consent.setEnabledFor(OWNER_A, false)
        assertTrue("re-enabling A should not be deferred by a canceled backfill", SyncScheduler.isBackfillDue(context, OWNER_A))
        assertFalse("A's opt-out must not reset B's marker", SyncScheduler.isBackfillDue(context, OWNER_B))

        consent.setEnabledFor(OWNER_A, true)
        assertTrue(SyncScheduler.isBackfillDue(context, OWNER_A))
        SyncScheduler.markBackfillScheduled(context, OWNER_A)
        consent.setEnabledFor(OWNER_A, true)
        assertFalse("repeated opt-in must not schedule an already-recorded backfill again", SyncScheduler.isBackfillDue(context, OWNER_A))
        assertFalse(SyncScheduler.isBackfillDue(context, OWNER_B))
    }

    private companion object {
        const val OWNER_A = "account-a"
        const val OWNER_B = "account-b"
        const val LEGACY_PREFS = "diarium_notifications"
        const val LEGACY_SYNC_ENABLED = "sync_enabled"
    }
}

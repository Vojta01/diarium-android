package cz.digitalnivedomi.diarium.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The key store's contract: a trimmed round-trip, a refusal to store blanks,
 * and a clean removal. The first two tests run against an in-memory slot (pure
 * logic, no Keystore); the last one exercises the production
 * [EncryptedSecretSlot] on a Robolectric-managed context, including the property
 * that a **second** store instance (a fresh app process) decrypts what the first
 * one wrote.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AiKeyStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** In-memory stand-in for the encrypted slot. */
    private class MemorySlot : SecretSlot {
        val values = mutableMapOf<String, String>()
        var legacyValue: String? = null
        override fun read(ownerId: String): String? = values[ownerId]
        override fun write(ownerId: String, value: String) {
            values[ownerId] = value
        }

        override fun erase(ownerId: String) {
            values.remove(ownerId)
        }

        override fun valueStatus(ownerId: String): LegacySecretPresence =
            if (values[ownerId].isNullOrBlank()) LegacySecretPresence.ABSENT else LegacySecretPresence.PRESENT

        override fun legacyValueStatus(): LegacySecretPresence =
            if (legacyValue != null) LegacySecretPresence.PRESENT else LegacySecretPresence.ABSENT
    }

    @Test
    fun `save, read and clear round-trip the trimmed key`() {
        val slot = MemorySlot()
        val store = AiKeyStore(slot, OWNER_A)

        assertFalse(store.hasKey())
        assertNull(store.key())

        assertTrue(store.saveKey("  sk-round-trip-123  "))
        assertEquals("the value is stored trimmed", "sk-round-trip-123", slot.values[OWNER_A])
        assertTrue(store.hasKey())
        assertEquals("sk-round-trip-123", store.key())

        store.clear()
        assertFalse(store.hasKey())
        assertNull(slot.values[OWNER_A])
    }

    @Test
    fun `a blank key is refused and nothing is written`() {
        val slot = MemorySlot()
        val store = AiKeyStore(slot, OWNER_A)

        assertFalse(store.saveKey("   "))
        assertFalse(store.saveKey(""))
        assertNull(slot.values[OWNER_A])
        assertFalse(store.hasKey())
    }

    @Test
    fun `the encrypted slot round-trips across store instances`() {
        // Robolectric ships no AndroidKeyStore provider; the fake stands in
        // for the device keystore so the production slot can run on the JVM.
        FakeAndroidKeyStore.install()
        val store = AiKeyStore(EncryptedSecretSlot(context), OWNER_A)
        // A previous test run may have left a key behind; start clean.
        store.clear()
        assertFalse(store.hasKey())

        assertTrue(store.saveKey("sk-encrypted-1"))

        // A fresh instance = a new app process: it must decrypt what was written.
        val reopened = AiKeyStore(EncryptedSecretSlot(context), OWNER_A)
        assertEquals("sk-encrypted-1", reopened.key())

        reopened.clear()
        assertFalse(store.hasKey())
        assertNull(store.key())
    }

    @Test
    fun `keys stay with their owner across account switch signout and process restart`() {
        val slot = MemorySlot()
        val accountA = AiKeyStore(slot, OWNER_A)
        val accountB = AiKeyStore(slot, OWNER_B)
        val signedOut = AiKeyStore(slot, null)

        assertTrue(accountA.saveKey("account-a-key"))
        assertNull(accountB.key())
        assertFalse(accountB.hasKey())
        assertFalse(signedOut.saveKey("unowned-key"))
        signedOut.clear()
        assertEquals("account-a-key", accountA.key())

        assertTrue(accountB.saveKey("account-b-key"))
        assertEquals("account-a-key", AiKeyStore(slot, OWNER_A).key())
        assertEquals("account-b-key", AiKeyStore(slot, OWNER_B).key())
    }

    @Test
    fun `legacy ownerless encrypted key is preserved but never exposed or removed`() {
        val slot = MemorySlot().apply { legacyValue = "legacy-key" }
        val account = AiKeyStore(slot, OWNER_A)
        val signedOut = AiKeyStore(slot, null)

        assertEquals(LegacySecretPresence.PRESENT, account.legacyValueStatus())
        assertNull(account.key())
        assertFalse(account.hasKey())
        assertNull(signedOut.key())
        assertFalse(account.saveKey("   "))
        account.clear()
        signedOut.clear()
        assertEquals("legacy-key", slot.legacyValue)
    }

    @Test
    fun `legacy key inspection failures remain unknown rather than absent`() {
        val slot = object : SecretSlot {
            override fun read(ownerId: String): String? = null
            override fun write(ownerId: String, value: String) = Unit
            override fun erase(ownerId: String) = Unit
            override fun legacyValueStatus(): LegacySecretPresence = error("synthetic inspection failure")
        }

        assertEquals(LegacySecretPresence.UNKNOWN, AiKeyStore(slot, OWNER_A).legacyValueStatus())
    }

    @Test
    fun `account key read failures remain unknown rather than absent`() {
        val slot = object : SecretSlot {
            override fun read(ownerId: String): String? = error("synthetic read failure")
            override fun write(ownerId: String, value: String) = Unit
            override fun erase(ownerId: String) = Unit
            override fun valueStatus(ownerId: String): LegacySecretPresence = error("synthetic read failure")
            override fun legacyValueStatus(): LegacySecretPresence = LegacySecretPresence.UNKNOWN
        }

        assertEquals(LegacySecretPresence.UNKNOWN, AiKeyStore(slot, OWNER_A).keyStatus())
    }

    private companion object {
        const val OWNER_A = "account-a"
        const val OWNER_B = "account-b"
    }
}

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
        var value: String? = null
        override fun read(): String? = value
        override fun write(value: String) {
            this.value = value
        }

        override fun erase() {
            value = null
        }
    }

    @Test
    fun `save, read and clear round-trip the trimmed key`() {
        val slot = MemorySlot()
        val store = AiKeyStore(slot)

        assertFalse(store.hasKey())
        assertNull(store.key())

        assertTrue(store.saveKey("  sk-round-trip-123  "))
        assertEquals("the value is stored trimmed", "sk-round-trip-123", slot.value)
        assertTrue(store.hasKey())
        assertEquals("sk-round-trip-123", store.key())

        store.clear()
        assertFalse(store.hasKey())
        assertNull(slot.value)
    }

    @Test
    fun `a blank key is refused and nothing is written`() {
        val slot = MemorySlot()
        val store = AiKeyStore(slot)

        assertFalse(store.saveKey("   "))
        assertFalse(store.saveKey(""))
        assertNull(slot.value)
        assertFalse(store.hasKey())
    }

    @Test
    fun `the encrypted slot round-trips across store instances`() {
        // Robolectric ships no AndroidKeyStore provider; the fake stands in
        // for the device keystore so the production slot can run on the JVM.
        FakeAndroidKeyStore.install()
        val store = AiKeyStore(EncryptedSecretSlot(context))
        // A previous test run may have left a key behind; start clean.
        store.clear()
        assertFalse(store.hasKey())

        assertTrue(store.saveKey("sk-encrypted-1"))

        // A fresh instance = a new app process: it must decrypt what was written.
        val reopened = AiKeyStore(EncryptedSecretSlot(context))
        assertEquals("sk-encrypted-1", reopened.key())

        reopened.clear()
        assertFalse(store.hasKey())
        assertNull(store.key())
    }
}

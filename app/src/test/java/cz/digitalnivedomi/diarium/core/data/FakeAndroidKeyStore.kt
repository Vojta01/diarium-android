package cz.digitalnivedomi.diarium.core.data

import android.security.keystore.KeyGenParameterSpec
import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.security.spec.AlgorithmParameterSpec
import java.util.Collections
import java.util.Date
import java.util.Enumeration
import javax.crypto.KeyGenerator
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey

/**
 * A fake `AndroidKeyStore` JCA provider for JVM unit tests.
 *
 * Robolectric ships no AndroidKeyStore (robolectric/robolectric#1518), so
 * [EncryptedSecretSlot] — via `androidx.security.crypto.MasterKeys` and Tink's
 * `AndroidKeysetManager` — would otherwise die with
 * `KeyStoreException: AndroidKeyStore not found`. This provider registers the
 * two services that path needs:
 *
 *  - `KeyStore.AndroidKeyStore` → [FakeKeyStore], an in-memory alias → key map;
 *  - `KeyGenerator.AES` (under the provider name `AndroidKeyStore`) →
 *    [FakeAesKeyGenerator], a JVM AES generator that stores the generated key
 *    under the alias taken from the given [KeyGenParameterSpec] — exactly what
 *    the real keystore does, and what makes "generate once, wrap the keyset,
 *    decrypt from a fresh instance" work.
 *
 * The key map is deliberately shared by all instances in the JVM: every store
 * instance sees the same "device" keys, mirroring the real device keystore.
 *
 * TEST-ONLY. Production code never references this class.
 */
object FakeAndroidKeyStore {

    private const val PROVIDER_NAME = "AndroidKeyStore"

    private val keys = mutableMapOf<String, SecretKey>()

    /** Registers the provider (idempotent; replaces a stale copy if present). */
    @Synchronized
    fun install() {
        // A previous Robolectric sandbox may have registered an equivalent
        // provider; drop it so Security's duplicate-name check cannot trip.
        Security.removeProvider(PROVIDER_NAME)
        Security.addProvider(AndroidKeyStoreProvider())
    }

    internal fun put(alias: String, key: SecretKey) {
        keys[alias] = key
    }

    internal fun get(alias: String): SecretKey? = keys[alias]

    internal fun remove(alias: String) {
        keys.remove(alias)
    }

    internal fun aliases(): List<String> = keys.keys.toList()

    private class AndroidKeyStoreProvider :
        Provider(PROVIDER_NAME, 1.0, "Fake AndroidKeyStore for JVM tests") {
        init {
            put("KeyStore.AndroidKeyStore", FakeKeyStore::class.java.name)
            put("KeyGenerator.AES", FakeAesKeyGenerator::class.java.name)
        }
    }

    /** KeyStoreSpi over the shared alias → key map. No certificates involved. */
    class FakeKeyStore : KeyStoreSpi() {
        override fun engineLoad(stream: InputStream?, password: CharArray?) = Unit
        override fun engineStore(stream: OutputStream?, password: CharArray?) = Unit
        override fun engineSize(): Int = keys.size
        override fun engineAliases(): Enumeration<String> = Collections.enumeration(aliases())
        override fun engineContainsAlias(alias: String?): Boolean =
            alias != null && keys.containsKey(alias)

        override fun engineDeleteEntry(alias: String?) {
            if (alias != null) remove(alias)
        }

        override fun engineGetKey(alias: String?, password: CharArray?): Key? = alias?.let { get(it) }
        override fun engineIsKeyEntry(alias: String?): Boolean = alias != null && keys.containsKey(alias)
        override fun engineIsCertificateEntry(alias: String?): Boolean = false
        override fun engineGetCertificate(alias: String?): Certificate? = null
        override fun engineGetCertificateChain(alias: String?): Array<Certificate>? = null

        override fun engineSetKeyEntry(
            alias: String?,
            key: Key?,
            password: CharArray?,
            chain: Array<out Certificate>?,
        ) {
            if (alias != null && key is SecretKey) put(alias, key)
        }

        override fun engineSetKeyEntry(alias: String?, key: ByteArray?, chain: Array<out Certificate>?) = Unit
        override fun engineSetCertificateEntry(alias: String?, cert: Certificate?) = Unit
        override fun engineGetCreationDate(alias: String?): Date = Date(0)
        override fun engineGetCertificateAlias(cert: Certificate?): String? = null
    }

    /**
     * Mirrors `MasterKeys.generateKey`: generates a JVM AES key and files it
     * under the spec's alias, so a later `keystore.getKey(alias, null)` — from
     * Tink's AndroidKeystoreAesGcm — finds it.
     */
    class FakeAesKeyGenerator : KeyGeneratorSpi() {
        private var alias: String? = null

        override fun engineInit(random: SecureRandom?) = Unit
        override fun engineInit(keysize: Int, random: SecureRandom?) = Unit
        override fun engineInit(params: AlgorithmParameterSpec?, random: SecureRandom?) {
            alias = (params as? KeyGenParameterSpec)?.keystoreAlias
        }

        override fun engineGenerateKey(): SecretKey {
            val key = jvmAesGenerator().generateKey()
            alias?.let { put(it, key) }
            return key
        }
    }

    /** A JVM AES generator that is guaranteed not to loop back into this provider. */
    private fun jvmAesGenerator(): KeyGenerator {
        for (provider in Security.getProviders()) {
            if (provider.name == PROVIDER_NAME) continue
            try {
                return KeyGenerator.getInstance("AES", provider.name)
            } catch (_: Exception) {
                // This provider does not do AES; try the next one.
            }
        }
        error("No JVM AES KeyGenerator available")
    }
}

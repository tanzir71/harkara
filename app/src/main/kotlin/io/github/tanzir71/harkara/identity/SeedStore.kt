package io.github.tanzir71.harkara.identity

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Where the 32-byte root seed lives. It never leaves the phone except inside a NIP-49 backup. */
interface SeedStore {
    /** The stored seed, or null when there is none yet. Throws [IOException] when a seed exists but cannot be read. */
    fun read(): ByteArray?

    fun write(seed: ByteArray)

    /** Deletes the seed and any key that protects it. */
    fun clear()
}

/** Seals the seed file with a key the app cannot export (AndroidKeyStore on real phones). */
interface SeedWrapper {
    fun wrap(plain: ByteArray): ByteArray

    fun unwrap(sealed: ByteArray): ByteArray

    fun destroy()
}

/**
 * The seed in an app-private file (`noBackupFilesDir/identity/root-seed`), written atomically.
 *
 * File: `u8 version (1) | u8 mode | body`, mode 0 = plain seed (no keystore, e.g. Robolectric),
 * mode 1 = AES-256-GCM from [wrapper]: `12-byte IV | ciphertext+tag`.
 */
class FileSeedStore(
    private val file: File,
    private val wrapper: SeedWrapper? = AndroidKeystoreSeedWrapper.createOrNull(),
) : SeedStore {
    @Synchronized
    override fun read(): ByteArray? {
        if (!file.exists()) return null
        val bytes = file.readBytes()
        if (bytes.size < 2 || bytes[0].toInt() != VERSION) throw IOException("unknown seed file format")
        val body = bytes.copyOfRange(2, bytes.size)
        val seed = when (bytes[1].toInt()) {
            MODE_PLAIN -> body
            MODE_WRAPPED -> {
                val w = wrapper ?: throw IOException("seed is sealed by a keystore that is not available")
                try {
                    w.unwrap(body)
                } catch (e: Exception) {
                    throw IOException("seed could not be unsealed", e)
                }
            }
            else -> throw IOException("unknown seed file mode")
        }
        if (seed.size != SEED_BYTES) throw IOException("seed has the wrong length")
        return seed
    }

    @Synchronized
    override fun write(seed: ByteArray) {
        require(seed.size == SEED_BYTES) { "seed must be $SEED_BYTES bytes" }
        val sealed = wrapper?.let { w -> runCatching { w.wrap(seed) }.getOrNull() }
        val content = if (sealed != null) byteArrayOf(VERSION.toByte(), MODE_WRAPPED.toByte()) + sealed
        else byteArrayOf(VERSION.toByte(), MODE_PLAIN.toByte()) + seed
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(content)
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) throw IOException("could not store the seed")
        }
    }

    @Synchronized
    override fun clear() {
        if (file.exists()) {
            // Overwrite before deleting; flash wear levelling means this is best effort.
            runCatching { file.writeBytes(ByteArray(file.length().toInt())) }
            file.delete()
        }
        runCatching { wrapper?.destroy() }
    }

    /** True when the stored seed is sealed by the keystore (false for plain files or no file). */
    fun isSealed(): Boolean = file.exists() && file.readBytes().let { it.size > 1 && it[1].toInt() == MODE_WRAPPED }

    companion object {
        const val SEED_BYTES = 32
        private const val VERSION = 1
        private const val MODE_PLAIN = 0
        private const val MODE_WRAPPED = 1
    }
}

/**
 * AES-256-GCM key in AndroidKeyStore. Returns null from [createOrNull] where there is no AndroidKeyStore
 * provider (JVM and Robolectric tests), so the store falls back to a plain app-private file.
 * Unverified on hardware: see docs/HARDWARE.md.
 */
class AndroidKeystoreSeedWrapper private constructor(private val keyStore: KeyStore) : SeedWrapper {
    private fun key(): SecretKey {
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    override fun wrap(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun unwrap(sealed: ByteArray): ByteArray {
        require(sealed.size > IV_BYTES) { "sealed seed too short" }
        val key = keyStore.getKey(ALIAS, null) as? SecretKey ?: throw IllegalStateException("keystore key is gone")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed, 0, IV_BYTES))
        return cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
    }

    override fun destroy() {
        if (keyStore.containsAlias(ALIAS)) keyStore.deleteEntry(ALIAS)
    }

    companion object {
        private const val PROVIDER = "AndroidKeyStore"
        private const val ALIAS = "harkara-root-seed"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12

        fun createOrNull(): AndroidKeystoreSeedWrapper? = runCatching {
            AndroidKeystoreSeedWrapper(KeyStore.getInstance(PROVIDER).apply { load(null) })
        }.getOrNull()
    }
}

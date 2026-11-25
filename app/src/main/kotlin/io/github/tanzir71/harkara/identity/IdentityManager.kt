package io.github.tanzir71.harkara.identity

import io.github.tanzir71.harkara.core.keys.KeyRing
import io.github.tanzir71.harkara.core.nostr.Bech32
import io.github.tanzir71.harkara.core.nostr.KeyPair
import io.github.tanzir71.harkara.core.nostr.Nip49
import io.github.tanzir71.harkara.core.nostr.Nip49Exception
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.security.SecureRandom

/**
 * Owns the root seed. Keys are made silently the first time anything needs them (no sign-up), every key
 * is derived through [KeyRing], and the only way the seed leaves the phone is [exportBackup]: the 32 seed
 * bytes in NIP-49's "private key" field, locked with the user's password.
 */
class IdentityManager(
    private val store: SeedStore,
    private val random: SecureRandom = SecureRandom(),
    private val backupLogN: Int = Nip49.DEFAULT_LOG_N,
) {
    private var ring: KeyRing? = null
    private val profileKey = MutableStateFlow<String?>(null)

    /** The profile pubkey (hex) once an identity exists; null before first use and after a wipe. */
    val profilePubkey: StateFlow<String?> = profileKey.asStateFlow()

    /** True when a seed is stored (without creating one). */
    @Synchronized
    fun hasIdentity(): Boolean = ring != null || runCatching { store.read() != null }.getOrDefault(true)

    /** The key ring, creating and storing a fresh seed on first use. */
    @Synchronized
    fun keyRing(): KeyRing {
        ring?.let { return it }
        val seed = try {
            store.read()
        } catch (_: IOException) {
            // A seed we can no longer unseal (keystore reset) cannot be recovered here; a backup can restore it.
            null
        } ?: KeyRing.generateSeed(random).also(store::write)
        return use(seed)
    }

    fun profile(): KeyPair = keyRing().profile()

    fun npub(): String = Bech32.encodeNpub(profile().pubkeyHex)

    /** `ncryptsec1…` of the root seed. Slow on purpose (scrypt): call it off the main thread. */
    fun exportBackup(password: String): String {
        require(password.isNotEmpty()) { "a backup needs a password" }
        val seed = storedSeed()
        return try {
            Nip49.encrypt(seed, password, logN = backupLogN, random = random)
        } finally {
            seed.fill(0)
        }
    }

    /**
     * Replaces the identity with the one in [ncryptsec]. Throws [Nip49Exception] for a wrong password or a
     * damaged backup and leaves the current identity untouched. Slow on purpose: call it off the main thread.
     */
    @Throws(Nip49Exception::class)
    fun restore(ncryptsec: String, password: String) {
        val seed = Nip49.decrypt(ncryptsec, password)
        if (seed.size != KeyRing.SEED_BYTES) throw Nip49Exception("backup does not hold a Harkara seed")
        synchronized(this) {
            store.write(seed)
            use(seed)
        }
    }

    /** Forgets the identity: seed file, keystore key and the keys held in memory. */
    @Synchronized
    fun wipe() {
        store.clear()
        ring = null
        profileKey.value = null
    }

    @Synchronized
    private fun storedSeed(): ByteArray {
        keyRing()
        return store.read() ?: throw IOException("no seed stored")
    }

    private fun use(seed: ByteArray): KeyRing {
        val keyRing = KeyRing(seed)
        seed.fill(0)
        ring = keyRing
        profileKey.value = keyRing.profile().pubkeyHex
        return keyRing
    }
}

/** `npub1abcd…wxyz`: the first and last four characters after the prefix. */
fun shortNpub(npub: String): String {
    val body = npub.removePrefix("npub1")
    return if (body.length <= 8) npub else "npub1${body.take(4)}…${body.takeLast(4)}"
}

package io.github.tanzir71.harkara.identity

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.tanzir71.harkara.AppContainer
import io.github.tanzir71.harkara.core.nostr.Nip49
import io.github.tanzir71.harkara.core.nostr.Nip49Exception
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** M3 gate: the app makes a key, backs it up with NIP-49, and restores it after a wipe. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class IdentityManagerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val seedFile = AppContainer.seedFile(context)

    @Before
    fun clean() {
        seedFile.parentFile?.deleteRecursively()
    }

    private fun manager() = IdentityManager(FileSeedStore(seedFile))

    @Test
    fun `create, back up, wipe and restore gives back the same keys`() {
        val identity = manager()
        assertFalse(identity.hasIdentity())

        val profile = identity.profile().pubkeyHex
        val area = identity.keyRing().area("wh0r0", "2026-W48").pubkeyHex
        assertTrue(seedFile.exists())
        assertEquals(profile, identity.profilePubkey.value)
        assertTrue(identity.npub().startsWith("npub1"))

        val backup = identity.exportBackup("bristi ashbe 2026")
        assertTrue(backup.startsWith("ncryptsec1"))
        assertEquals(Nip49.DEFAULT_LOG_N, Nip49.inspect(backup).first)

        identity.wipe()
        assertFalse(seedFile.exists())
        assertFalse(identity.hasIdentity())
        assertNull(identity.profilePubkey.value)

        val fresh = manager()
        fresh.restore(backup, "bristi ashbe 2026")
        assertEquals(profile, fresh.profile().pubkeyHex)
        assertEquals(area, fresh.keyRing().area("wh0r0", "2026-W48").pubkeyHex)
        assertEquals(profile, fresh.profilePubkey.value)

        // The restored seed is what is stored now: a cold start sees the same identity.
        assertEquals(profile, manager().profile().pubkeyHex)
    }

    @Test
    fun `wrong password fails cleanly and keeps the current identity`() {
        val old = manager()
        val backup = old.exportBackup("correct password")
        old.wipe()
        val current = manager()
        val currentKey = current.profile().pubkeyHex

        assertThrows(Nip49Exception::class.java) { current.restore(backup, "wrong password") }
        assertThrows(Nip49Exception::class.java) { current.restore("ncryptsec1notabackup", "correct password") }

        assertEquals(currentKey, current.profile().pubkeyHex)
        assertEquals(currentKey, manager().profile().pubkeyHex)
    }

    @Test
    fun `the seed is made once, silently, and survives restarts`() {
        val first = manager().profile().pubkeyHex
        assertEquals(first, manager().profile().pubkeyHex)
        assertEquals("version + mode + 32 bytes; Robolectric has no AndroidKeyStore", 34L, seedFile.length())
    }

    @Test
    fun `keystore-sealed seeds round trip, and a lost keystore key leads to a new identity`() {
        val wrapper = FakeWrapper()
        val store = FileSeedStore(seedFile, wrapper)
        val seed = ByteArray(32) { (it + 1).toByte() }

        store.write(seed)

        assertTrue(store.isSealed())
        assertFalse("seed is not in the file in clear", seedFile.readBytes().toList().windowed(32).any { it == seed.toList() })
        assertArrayEquals(seed, store.read())
        val before = IdentityManager(store).profile().pubkeyHex

        wrapper.destroy()
        assertThrows(IOException::class.java) { store.read() }
        assertNotEquals(before, IdentityManager(FileSeedStore(seedFile, wrapper)).profile().pubkeyHex)
    }

    @Test
    fun `panic wipe also wipes the seed`() = runBlocking {
        val container = AppContainer(context, inMemoryDatabase = true)
        val before = container.identity.profile().pubkeyHex
        assertTrue(seedFile.exists())

        container.panicWipe()

        assertFalse(seedFile.exists())
        assertFalse(container.identity.hasIdentity())
        assertNull(container.identity.profilePubkey.value)
        assertNotEquals(before, container.identity.profile().pubkeyHex)
    }

    @Test
    fun `short npub keeps four characters each side`() {
        assertEquals("npub180cv…h6w6", shortNpub("npub180cvv07tjdrrgpa0j7j7tmnyl2yr6yr7l8j4s3evf6u64th6gkwsyjh6w6"))
    }

    /** AES-GCM with an in-memory key, standing in for AndroidKeyStore. */
    private class FakeWrapper : SeedWrapper {
        private var key: ByteArray? = ByteArray(32) { 7 }

        override fun wrap(plain: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key ?: ByteArray(32) { 9 }.also { key = it }, "AES"))
            return cipher.iv + cipher.doFinal(plain)
        }

        override fun unwrap(sealed: ByteArray): ByteArray {
            val k = key ?: throw IllegalStateException("key destroyed")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(k, "AES"), GCMParameterSpec(128, sealed, 0, 12))
            return cipher.doFinal(sealed, 12, sealed.size - 12)
        }

        override fun destroy() {
            key = null
        }
    }
}

package io.github.tanzir71.harkara.tools.lan

import io.github.tanzir71.harkara.core.tools.PttChunk
import io.github.tanzir71.harkara.core.tools.PttFormat
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class LanPushToTalkTest {
    @Test fun `construction enable and off never touch microphone and release unblocks held capture`() {
        val starts = AtomicInteger()
        val entered = CountDownLatch(1)
        val unblocked = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val sends = AtomicInteger()
        val microphone = object : PttMicrophone {
            override fun start(): Boolean { starts.incrementAndGet(); return true }
            override fun read(buffer: ByteArray): Int {
                entered.countDown()
                assertTrue(unblocked.await(2, TimeUnit.SECONDS))
                return -1
            }
            override fun close() { unblocked.countDown(); closed.countDown() }
        }
        LanPushToTalk({ microphone }, { object : PttChunkTransport {
            override fun send(chunk: PttChunk) { sends.incrementAndGet() }
            override fun close() {}
        } }).use { ptt ->
            assertFalse(ptt.press())
            assertEquals(0, starts.get())
            ptt.enable()
            assertEquals(0, starts.get())
            assertTrue(ptt.press())
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertTrue(ptt.isHeld)
            ptt.release()
            assertTrue(closed.await(2, TimeUnit.SECONDS))
            assertFalse(ptt.isHeld)
            assertEquals(0, sends.get())
            ptt.close()
            assertFalse(ptt.press())
        }
    }

    @Test fun `bounded chunks cap a hold and close microphone and transport without audio queue`() {
        val sent = AtomicInteger()
        val micClosed = CountDownLatch(1)
        val transportClosed = CountDownLatch(1)
        LanPushToTalk({ object : PttMicrophone {
            override fun start() = true
            override fun read(buffer: ByteArray) = buffer.size
            override fun close() { micClosed.countDown() }
        } }, { object : PttChunkTransport {
            override fun send(chunk: PttChunk) {
                assertEquals(PttFormat.CHUNK_BYTES, chunk.pcm.size)
                assertEquals(sent.getAndIncrement(), chunk.sequence)
            }
            override fun close() { transportClosed.countDown() }
        } }).use { ptt ->
            ptt.enable()
            assertTrue(ptt.press())
            assertTrue(micClosed.await(2, TimeUnit.SECONDS))
            assertTrue(transportClosed.await(2, TimeUnit.SECONDS))
            assertEquals(PttFormat.MAX_CHUNKS, sent.get())
            assertFalse(ptt.isHeld)
        }
    }

    @Test fun `permission denial and failed transport close capture and do not restart it`() {
        val starts = AtomicInteger()
        val closed = CountDownLatch(1)
        val failed = CountDownLatch(1)
        LanPushToTalk({ object : PttMicrophone {
            override fun start(): Boolean { starts.incrementAndGet(); return false }
            override fun read(buffer: ByteArray): Int = error("Read without permission")
            override fun close() { closed.countDown() }
        } }, { object : PttChunkTransport {
            override fun send(chunk: PttChunk) = error("Send without permission")
            override fun close() {}
        } }, { failed.countDown() }).use { ptt ->
            ptt.enable()
            assertTrue(ptt.press())
            assertTrue(failed.await(2, TimeUnit.SECONDS))
            assertTrue(closed.await(2, TimeUnit.SECONDS))
            assertEquals(1, starts.get())
            assertFalse(ptt.isHeld)
        }
    }
}

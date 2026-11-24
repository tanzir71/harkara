package io.github.tanzir71.harkara.core.nostr

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** NIP-19 examples, plus keys from the NIP-59 example. */
class Bech32Test {
    @Test
    fun `npub vectors`() {
        assertEquals("npub180cvv07tjdrrgpa0j7j7tmnyl2yr6yr7l8j4s3evf6u64th6gkwsyjh6w6", Bech32.encodeNpub("3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d"))
        assertEquals("7e7e9c42a91bfef19fa929e5fda1b72e0ebc1a4c1141673e2794234d86addf4e", Bech32.decodeNpub("npub10elfcs4fr0l0r8af98jlmgdh9c8tcxjvz9qkw038js35mp4dma8qzvjptg"))
        assertEquals("npub10elfcs4fr0l0r8af98jlmgdh9c8tcxjvz9qkw038js35mp4dma8qzvjptg", Bech32.encodeNpub("7e7e9c42a91bfef19fa929e5fda1b72e0ebc1a4c1141673e2794234d86addf4e"))
    }

    @Test
    fun `nsec vectors`() {
        val hex = "67dea2ed018072d675f5415ecfaed7d2597555e202d85b3d65ea4e58d2d92ffa"
        assertEquals(hex, Hex.encode(Bech32.decodeNsec("nsec1vl029mgpspedva04g90vltkh6fvh240zqtv9k0t9af8935ke9laqsnlfe5")))
        assertEquals("nsec1vl029mgpspedva04g90vltkh6fvh240zqtv9k0t9af8935ke9laqsnlfe5", Bech32.encodeNsec(Hex.decode(hex)))
        assertEquals(NipExamples.NIP59_AUTHOR_SECRET, Hex.encode(Bech32.decodeNsec(NipExamples.NIP59_AUTHOR_NSEC)))
        assertEquals(NipExamples.NIP59_RECIPIENT_SECRET, Hex.encode(Bech32.decodeNsec(NipExamples.NIP59_RECIPIENT_NSEC)))
    }

    @Test
    fun `nprofile vector`() {
        val profile = Bech32.decodeNprofile("nprofile1qqsrhuxx8l9ex335q7he0f09aej04zpazpl0ne2cgukyawd24mayt8gpp4mhxue69uhhytnc9e3k7mgpz4mhxue69uhkg6nzv9ejuumpv34kytnrdaksjlyr9p")

        assertEquals("3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d", profile.pubkey)
        assertEquals(listOf("wss://r.x.com", "wss://djbas.sadkb.com"), profile.relays)
        assertEquals(profile, Bech32.decodeNprofile(Bech32.encodeNprofile(profile)))
    }

    @Test
    fun `note and nevent round trips`() {
        val id = "000006d8c378af1779d2feebc7603a125d99eca0ccf1085959b307f64e5dd358"
        assertEquals(id, Bech32.decodeNote(Bech32.encodeNote(id)))
        val nevent = Nip19Event(id, listOf("wss://relay.damus.io"), "a48380f4cfcc1ad5378294fcac36439770f9c878dd880ffa94bb74ea54a6f243", 1)
        assertEquals(nevent, Bech32.decodeNevent(Bech32.encodeNevent(nevent)))
    }

    @Test
    fun `errors are caught`() {
        val npub = "npub180cvv07tjdrrgpa0j7j7tmnyl2yr6yr7l8j4s3evf6u64th6gkwsyjh6w6"
        assertThrows(IllegalArgumentException::class.java) { Bech32.decodeNpub(npub.dropLast(1) + "7") }
        assertThrows(IllegalArgumentException::class.java) { Bech32.decodeNsec(npub) }
        assertThrows(IllegalArgumentException::class.java) { Bech32.decode("Npub180cvv07tjdrrgpa0j7j7tmnyl2yr6yr7l8j4s3evf6u64th6gkwsyjh6w6") }
        assertArrayEquals(Hex.decode("3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d"), Bech32.decode(npub.uppercase()).second)
    }
}

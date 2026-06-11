package cz.arnal.bleedge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ChannelCryptoTest {

    @Test
    fun publicChannelHashIs0x11() {
        // MeshCore's documented value: SHA256(publicPsk)[0] == 0x11.
        assertEquals(0x11.toByte(), ChannelCrypto.channelHash(ChannelCrypto.PUBLIC_PSK))
    }

    @Test
    fun namedChannelSecretIsSha256Prefix() {
        val secret = ChannelCrypto.deriveChannelSecret("test")
        assertEquals(ChannelCrypto.SECRET_LEN, secret.size)
    }

    @Test
    fun sealOpenRoundTrip() {
        val psk = ChannelCrypto.deriveChannelSecret("rock climbers")
        val payload = ChannelCrypto.seal(psk, "Maya", "see you at 8am 🧗", timestamp = 1_700_000_000)
        // First byte must be the channel hash.
        assertEquals(ChannelCrypto.channelHash(psk), payload[0])

        val d = ChannelCrypto.open(psk, payload)
        assertNotNull(d)
        assertEquals("Maya", d!!.sender)
        assertEquals("see you at 8am 🧗", d.text)
        assertEquals(1_700_000_000L, d.timestamp)
    }

    @Test
    fun wrongChannelKeyFails() {
        val a = ChannelCrypto.deriveChannelSecret("alpha")
        val b = ChannelCrypto.deriveChannelSecret("beta")
        val payload = ChannelCrypto.seal(a, "x", "hi")
        assertNull(ChannelCrypto.open(b, payload))
    }

    @Test
    fun publicChannelRoundTrip() {
        val payload = ChannelCrypto.seal(ChannelCrypto.PUBLIC_PSK, "Bob", "hello world")
        assertEquals(0x11.toByte(), payload[0])
        val d = ChannelCrypto.open(ChannelCrypto.PUBLIC_PSK, payload)
        assertEquals("hello world", d?.text)
    }
}

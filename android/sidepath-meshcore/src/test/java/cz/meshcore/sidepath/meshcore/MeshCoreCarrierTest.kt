package cz.meshcore.sidepath.meshcore

import cz.meshcore.sidepath.protocol.Sidepath
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Host JVM tests for the SPMC carrier framing. These must stay in lockstep with the Go
 * implementation (`core/meshcore_carrier_test.go`) — the cross-impl vector is the shared anchor.
 */
class MeshCoreCarrierTest {
    @Test
    fun roundTrip() {
        val raw = byteArrayOf(0x10, 0x00, 0xde.toByte(), 0xad.toByte(), 0xbe.toByte(), 0xef.toByte())
        val framed = MeshCoreCarrier.frame("CZ", raw)
        val (code, got) = MeshCoreCarrier.unframe(framed)
        assertEquals("CZ", code)
        assertArrayEquals(raw, got)
    }

    @Test
    fun legacyRawUnframes() {
        val raw = byteArrayOf(0x10, 0x00, 0x01, 0x02, 0x03)
        val (code, got) = MeshCoreCarrier.unframe(raw)
        assertEquals("", code)
        assertArrayEquals(raw, got)
    }

    @Test
    fun frameRejectsBadCode() {
        val raw = byteArrayOf(0x01, 0x02)
        assertArrayEquals(raw, MeshCoreCarrier.frame("", raw))
        assertArrayEquals(raw, MeshCoreCarrier.frame("TOOLONG", raw))
    }

    @Test
    fun unframeGuards() {
        val tooBigLen = (Sidepath.MAX_NETWORK_CODE_BYTES + 1).toByte()
        val cases = listOf(
            byteArrayOf('S'.code.toByte(), 'P'.code.toByte(), 'M'.code.toByte()),                                   // too short
            byteArrayOf('X'.code.toByte(), 'P'.code.toByte(), 'M'.code.toByte(), 'C'.code.toByte(), 1, 1, 'C'.code.toByte(), 0xaa.toByte()), // wrong magic
            byteArrayOf('S'.code.toByte(), 'P'.code.toByte(), 'M'.code.toByte(), 'C'.code.toByte(), 99, 1, 'C'.code.toByte(), 0xaa.toByte()), // wrong version
            byteArrayOf('S'.code.toByte(), 'P'.code.toByte(), 'M'.code.toByte(), 'C'.code.toByte(), 1, 0, 0xaa.toByte()),                     // zero code len
            byteArrayOf('S'.code.toByte(), 'P'.code.toByte(), 'M'.code.toByte(), 'C'.code.toByte(), 1, tooBigLen, 'C'.code.toByte()),         // code len too big
            byteArrayOf('S'.code.toByte(), 'P'.code.toByte(), 'M'.code.toByte(), 'C'.code.toByte(), 1, 4, 'C'.code.toByte(), 'Z'.code.toByte()), // truncated code
        )
        for (payload in cases) {
            val (code, got) = MeshCoreCarrier.unframe(payload)
            assertEquals("", code)
            assertArrayEquals(payload, got)
        }
    }

    /** Go frames these exact bytes; Kotlin must unframe the identical vector. */
    @Test
    fun crossImplVector() {
        val raw = byteArrayOf(0xde.toByte(), 0xad.toByte(), 0xbe.toByte(), 0xef.toByte())
        val expectedFramed = byteArrayOf(
            'S'.code.toByte(), 'P'.code.toByte(), 'M'.code.toByte(), 'C'.code.toByte(),
            1, 2, 'C'.code.toByte(), 'Z'.code.toByte(),
            0xde.toByte(), 0xad.toByte(), 0xbe.toByte(), 0xef.toByte(),
        )
        assertArrayEquals(expectedFramed, MeshCoreCarrier.frame("CZ", raw))

        val (code, got) = MeshCoreCarrier.unframe(expectedFramed)
        assertEquals("CZ", code)
        assertArrayEquals(raw, got)
    }
}

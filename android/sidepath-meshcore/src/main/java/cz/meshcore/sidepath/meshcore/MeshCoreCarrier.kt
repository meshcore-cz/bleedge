package cz.meshcore.sidepath.meshcore

import cz.meshcore.sidepath.protocol.Sidepath

/**
 * SPMC carrier framing for bridged MeshCore packets. A `MESHCORE_PACKET` datagram payload is, by
 * default, the raw MeshCore over-the-air packet carried verbatim. When a bridge knows the external
 * network a packet was heard on, it MAY prepend a small, self-describing frame so receivers can
 * attribute the packet to that network immediately, without waiting for the bridge's signed ANNOUNCE.
 * Absence of the frame == legacy raw payload (back-compat). See `docs/PROTOCOL.md` §13.1.
 *
 * Layout (only when the bridge has a single network):
 * ```
 * magic        [4]  ASCII "SPMC"                     # Sidepath-MeshCore bridged marker
 * version      [1]  = VERSION
 * code_len     [1]  uint8 (1..MAX_NETWORK_CODE_BYTES)
 * code_utf8    [code_len]
 * raw_meshcore [...]                                 # the original OTA packet, unchanged
 * ```
 *
 * Critical invariant: dedup and CoreScope content hashing operate on the inner raw packet (see
 * [Unframed.raw]), never the framed payload — so the same packet bridged by a legacy (raw) bridge
 * and a new (framed) bridge dedups identically across a mixed fleet.
 *
 * The embedded code is UNSIGNED — a carrier could mislabel it. That is acceptable because a malicious
 * carrier can already forge announces; the signed-announce path remains the trustworthy source when
 * present.
 *
 * This must stay byte-for-byte compatible with the Go implementation (`core/meshcore_carrier.go`).
 */
object MeshCoreCarrier {
    /** SPMC frame version this build emits and accepts. */
    const val VERSION: Int = 1

    /** The 4-byte marker distinguishing a framed payload from a legacy raw MeshCore packet. */
    private val MAGIC = byteArrayOf('S'.code.toByte(), 'P'.code.toByte(), 'M'.code.toByte(), 'C'.code.toByte())

    /** Fixed-size prefix length: magic(4) + version(1) + code_len(1). */
    private const val HEADER = 6

    /**
     * A decoded carrier payload: the embedded [networkCode] ("" when absent / legacy raw) and the
     * inner [raw] MeshCore over-the-air packet.
     */
    data class Unframed(val networkCode: String, val raw: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is Unframed && networkCode == other.networkCode && raw.contentEquals(other.raw)

        override fun hashCode(): Int = 31 * networkCode.hashCode() + raw.contentHashCode()
    }

    /**
     * Prepend the SPMC frame to [raw], tagging it with [code]. If [code] is empty or too long to
     * frame, [raw] is returned unchanged (the legacy raw form).
     */
    fun frame(code: String, raw: ByteArray): ByteArray {
        val cb = code.toByteArray(Charsets.UTF_8)
        if (cb.isEmpty() || cb.size > Sidepath.MAX_NETWORK_CODE_BYTES) return raw
        return ByteArray(HEADER + cb.size + raw.size).also { out ->
            MAGIC.copyInto(out, 0)
            out[4] = VERSION.toByte()
            out[5] = cb.size.toByte()
            cb.copyInto(out, HEADER)
            raw.copyInto(out, HEADER + cb.size)
        }
    }

    /**
     * Split a `MESHCORE_PACKET` datagram [payload] into the embedded network code (if any) and the
     * inner raw MeshCore packet. A payload without a valid SPMC frame is treated as legacy raw,
     * yielding `Unframed("", payload)`.
     */
    fun unframe(payload: ByteArray): Unframed {
        if (payload.size < HEADER) return Unframed("", payload)
        for (i in MAGIC.indices) if (payload[i] != MAGIC[i]) return Unframed("", payload)
        if ((payload[4].toInt() and 0xFF) != VERSION) return Unframed("", payload)
        val codeLen = payload[5].toInt() and 0xFF
        if (codeLen < 1 || codeLen > Sidepath.MAX_NETWORK_CODE_BYTES || HEADER + codeLen > payload.size) {
            return Unframed("", payload)
        }
        val code = String(payload, HEADER, codeLen, Charsets.UTF_8)
        val raw = payload.copyOfRange(HEADER + codeLen, payload.size)
        return Unframed(code, raw)
    }
}

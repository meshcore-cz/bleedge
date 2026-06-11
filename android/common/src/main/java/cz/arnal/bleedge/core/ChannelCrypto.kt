package cz.arnal.bleedge.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * MeshCore-compatible group/channel messaging, matching `meshcore-cz/meshpkt`.
 *
 * A channel is identified by a 16-byte pre-shared key (PSK). Messages are wrapped
 * in the MeshCore GRP_TXT payload so they are wire-compatible with MeshCore:
 *
 *   payload = [channelHash:1][mac:2][ciphertext]
 *   plaintext = timestamp(4 LE) | flags(1) | "SenderName: MessageText"  (zero-padded to 16)
 *   ciphertext = AES-128-ECB(secret, plaintext)
 *   mac = HMAC-SHA256(key = secret16 ‖ zero16, ciphertext)[:2]
 *   channelHash = SHA256(secret)[0]
 *
 * Channel kinds:
 *   - Public: the well-known PSK [PUBLIC_PSK] (hash 0x11).
 *   - Named ("public hash"): secret = SHA256(name)[:16] via [deriveChannelSecret].
 *   - Secret: a user-supplied 16-byte PSK (or derived from a passphrase).
 */
object ChannelCrypto {
    const val SECRET_LEN = 16
    private const val MAC_LEN = 2

    /** MeshCore's default Public channel PSK (16 bytes); channel hash = 0x11. */
    val PUBLIC_PSK: ByteArray = hexToBytes("8b3387e9c5cdea6ac9e5edbaa115cd72")

    /** Derives the 16-byte PSK for a name-only ("public hash") channel: SHA-256(name)[:16]. */
    fun deriveChannelSecret(name: String): ByteArray =
        sha256(name.toByteArray(Charsets.UTF_8)).copyOf(SECRET_LEN)

    /** The 1-byte routing hash the firmware uses to match a packet to a channel: SHA-256(secret)[0]. */
    fun channelHash(secret: ByteArray): Byte = sha256(secret.copyOf(SECRET_LEN))[0]

    /**
     * Seals a channel message into a MeshCore GRP_TXT payload.
     * [timestamp] is unix seconds (defaults to now).
     */
    fun seal(
        secret: ByteArray,
        sender: String,
        text: String,
        timestamp: Long = System.currentTimeMillis() / 1000,
    ): ByteArray {
        val body = "$sender: $text".toByteArray(Charsets.UTF_8)
        val plain = ByteBuffer.allocate(4 + 1 + body.size).order(ByteOrder.LITTLE_ENDIAN)
        plain.putInt(timestamp.toInt())
        plain.put(0) // flags: text type 0, attempt 0
        plain.put(body)
        val padded = zeroPad(plain.array(), 16)

        val ct = aesEcb(Cipher.ENCRYPT_MODE, secret.copyOf(SECRET_LEN), padded)
        val mac = mac2(secret, ct)

        val out = ByteArray(1 + MAC_LEN + ct.size)
        out[0] = channelHash(secret)
        System.arraycopy(mac, 0, out, 1, MAC_LEN)
        System.arraycopy(ct, 0, out, 1 + MAC_LEN, ct.size)
        return out
    }

    /** A decoded channel message. */
    data class Decoded(val sender: String, val text: String, val timestamp: Long)

    /**
     * Opens a GRP_TXT payload with [secret]. Returns null if the channel hash doesn't
     * match, the MAC fails, or the plaintext is malformed (i.e. not this channel).
     */
    fun open(secret: ByteArray, payload: ByteArray): Decoded? {
        if (payload.size < 1 + MAC_LEN) return null
        if (payload[0] != channelHash(secret)) return null
        val ct = payload.copyOfRange(1 + MAC_LEN, payload.size)
        if (ct.isEmpty() || ct.size % 16 != 0) return null
        val mac = payload.copyOfRange(1, 1 + MAC_LEN)
        if (!constantTimeEquals(mac, mac2(secret, ct))) return null

        val pt = aesEcb(Cipher.DECRYPT_MODE, secret.copyOf(SECRET_LEN), ct)
        if (pt.size < 5) return null
        val ts = ByteBuffer.wrap(pt, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
        // body = bytes after [ts(4)|flags(1)], with trailing zero padding stripped.
        var end = pt.size
        while (end > 5 && pt[end - 1] == 0.toByte()) end--
        val body = String(pt, 5, end - 5, Charsets.UTF_8)
        val sep = body.indexOf(": ")
        val sender = if (sep >= 0) body.substring(0, sep) else ""
        val text = if (sep >= 0) body.substring(sep + 2) else body
        return Decoded(sender, text, ts)
    }

    // ---- primitives ----------------------------------------------------------

    private fun aesEcb(mode: Int, key: ByteArray, data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/ECB/NoPadding")
        c.init(mode, SecretKeySpec(key, "AES"))
        return c.doFinal(data)
    }

    private fun mac2(secret: ByteArray, ciphertext: ByteArray): ByteArray {
        val key = ByteArray(32)
        System.arraycopy(secret, 0, key, 0, SECRET_LEN) // secret16 ‖ zero16
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(ciphertext).copyOf(MAC_LEN)
    }

    private fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)

    private fun zeroPad(data: ByteArray, block: Int): ByteArray {
        val rem = data.size % block
        if (rem == 0) return data
        return data.copyOf(data.size + (block - rem))
    }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var r = 0
        for (i in a.indices) r = r or (a[i].toInt() xor b[i].toInt())
        return r == 0
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

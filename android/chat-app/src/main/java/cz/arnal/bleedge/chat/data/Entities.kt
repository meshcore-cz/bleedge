package cz.arnal.bleedge.chat.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A conversation key for a channel is "ch:" + the channel's PSK hex. */
fun channelPeerId(pskHex: String): String = "ch:$pskHex"
fun isChannelPeer(peerHex: String): Boolean = peerHex.startsWith("ch:")
fun channelPskHexOf(peerHex: String): String = peerHex.removePrefix("ch:")

/** Channel kinds offered in the Join dialog. */
object ChannelKind {
    const val PUBLIC = "public"
    const val NAMED = "named"  // PSK derived from the name (SHA-256(name)[:16])
    const val SECRET = "secret"
}

/**
 * A joined MeshCore-compatible channel, identified by its 16-byte PSK ([pskHex], 32 hex
 * chars). [hashByte] is the 1-byte channel hash (0..255) used to match inbound packets.
 */
@Entity(tableName = "channels")
data class Channel(
    @PrimaryKey val pskHex: String,
    val name: String,
    val hashByte: Int,
    val kind: String,
)

/** Outgoing-message delivery state. */
object MsgStatus {
    const val SENDING = 0
    const val SENT = 1       // transmitted to the mesh
    const val DELIVERED = 2  // recipient ACKed (direct messages only)
    const val FAILED = 3     // could not be sent (e.g. unknown public key)
}

/**
 * A peer we have learned about (from its ANNOUNCE) or chatted with.
 * [pubKeyHex] is the 32-byte Ed25519 key used to encrypt direct messages.
 */
@Entity(tableName = "contacts")
data class Contact(
    @PrimaryKey val nodeHex: String,
    val pubKeyHex: String,
    val description: String,
)

/**
 * One chat message. [id] is the mesh packet id (hex) so an inbound delivery ACK can be
 * matched back to the outgoing message it confirms. [peerHex] is the conversation:
 * the other node's id for a direct chat, or "ch:"+pskHex for a channel (see [channelPeerId]).
 * [routeHex] is a comma-separated hop path (the trace the packet took, or for a
 * delivered DM the route the ACK returned along).
 */
@Entity(tableName = "messages")
data class Message(
    @PrimaryKey val id: String,
    val peerHex: String,
    val senderHex: String = "",  // originating node id (hex)
    val senderName: String = "", // display name of the sender (from a channel message's plaintext)
    val incoming: Boolean,
    val text: String,
    val timestampMs: Long,
    val status: Int = MsgStatus.SENT,
    val routeHex: String = "",
    val read: Boolean = false,
)

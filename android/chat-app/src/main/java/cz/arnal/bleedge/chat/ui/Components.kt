package cz.arnal.bleedge.chat.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.arnal.bleedge.chat.AvatarStyle
import cz.arnal.bleedge.chat.ChatViewModel
import cz.arnal.bleedge.chat.ConnState
import cz.arnal.bleedge.chat.data.ChannelKind
import cz.arnal.bleedge.chat.data.Message
import cz.arnal.bleedge.chat.data.MsgStatus
import cz.arnal.bleedge.chat.data.isChannelPeer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val avatarColors = listOf(
    Color(0xFF6750A4), Color(0xFF1565C0), Color(0xFF2E7D32), Color(0xFFB71C1C),
    Color(0xFFEF6C00), Color(0xFF00838F), Color(0xFFAD1457), Color(0xFF4527A0),
)

/** How avatars are drawn app-wide; provided at the root from the user's setting. */
val LocalAvatarStyle = staticCompositionLocalOf { AvatarStyle.IDENTICON }

@Composable
fun Avatar(seed: String, label: String, size: Int = 44, onClick: (() -> Unit)? = null) {
    val base = Modifier.size(size.dp).clip(CircleShape)
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    if (LocalAvatarStyle.current == AvatarStyle.IDENTICON) {
        Identicon(seed, base)
    } else {
        val color = avatarColors[(seed.hashCode().ushr(1)) % avatarColors.size]
        val initials = label.trim().take(2).uppercase().ifBlank { "?" }
        Box(base.background(color), contentAlignment = Alignment.Center) {
            Text(initials, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = (size / 2.6).sp)
        }
    }
}

/** A deterministic GitHub-style identicon: a 5×5 left-right-mirrored grid hashed from [seed]. */
@Composable
private fun Identicon(seed: String, modifier: Modifier) {
    val digest = remember(seed) { java.security.MessageDigest.getInstance("MD5").digest(seed.toByteArray()) }
    val fg = remember(seed) {
        val hue = (digest[0].toInt() and 0xFF) / 255f * 360f
        Color.hsv(hue, 0.55f, 0.85f)
    }
    val bg = Color(0xFFEDEDED)
    Canvas(modifier.background(bg)) {
        val cell = size.minDimension / 5f
        for (row in 0 until 5) {
            for (col in 0 until 3) {
                val on = (digest[row * 3 + col].toInt() and 1) == 0
                if (!on) continue
                for (c in intArrayOf(col, 4 - col)) {
                    drawRect(
                        color = fg,
                        topLeft = Offset(c * cell, row * cell),
                        size = Size(cell, cell),
                    )
                }
            }
        }
    }
}

/** Renders a public key (hex) compactly as `<e9534284...49e01345>`; blank in → blank out. */
fun formatPubKey(hex: String): String = when {
    hex.isBlank() -> ""
    hex.length >= 16 -> "<${hex.take(8)}...${hex.takeLast(8)}>"
    else -> "<$hex>"
}

/**
 * Channel display label: only name-derived ("public hash") channels get the `#` prefix
 * (no space, e.g. `#test`); Public and Secret channels show their name as-is.
 */
fun channelLabel(name: String, kind: String): String =
    if (kind == ChannelKind.NAMED) "#$name" else name

/** Channel mentions are stored/transmitted as `@[Name]`; this matches one. */
val mentionRegex = Regex("""@\[([^\]]+)\]""")

/**
 * Number of intermediate relays a packet passed through. The stored route is the packet
 * trace, whose last hop is always this node, so relays = hops − 1 (0 = direct).
 */
fun relayCount(routeHex: String): Int =
    (routeHex.split(",").count { it.isNotBlank() } - 1).coerceAtLeast(0)

/** Compact "direct / N relays" chip shown on a message bubble. */
@Composable
fun RouteIndicator(routeHex: String) {
    if (routeHex.isBlank()) return
    val relays = relayCount(routeHex)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Icon(
            if (relays == 0) Icons.Default.Bolt else Icons.Default.Route,
            contentDescription = if (relays == 0) "Direct" else "$relays relays",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
        Text(
            if (relays == 0) "direct" else "$relays",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Colored connection-health dot shown next to the app name. */
@Composable
fun ConnectionDot(state: ConnState, size: Int = 10) {
    val color = when (state) {
        ConnState.CONNECTED -> Color(0xFF2E7D32) // green
        ConnState.NO_PEERS -> Color(0xFFEF6C00)  // orange
        ConnState.OFFLINE -> Color(0xFF9E9E9E)   // grey
        ConnState.ERROR -> Color(0xFFC62828)     // red
    }
    Box(Modifier.size(size.dp).clip(CircleShape).background(color))
}

@Composable
fun DeliveryTick(status: Int) {
    val (icon, tint) = when (status) {
        MsgStatus.SENDING -> Icons.Default.Schedule to MaterialTheme.colorScheme.onSurfaceVariant
        MsgStatus.SENT -> Icons.Default.Done to MaterialTheme.colorScheme.onSurfaceVariant
        MsgStatus.DELIVERED -> Icons.Default.DoneAll to MaterialTheme.colorScheme.primary
        else -> Icons.Default.ErrorOutline to MaterialTheme.colorScheme.error
    }
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(15.dp))
}

private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
private val dayFmt = SimpleDateFormat("MMM d", Locale.getDefault())

fun formatClock(ts: Long): String = timeFmt.format(Date(ts))

fun formatRelative(ts: Long): String {
    val now = System.currentTimeMillis()
    val sameDay = dayFmt.format(Date(ts)) == dayFmt.format(Date(now))
    return if (sameDay) timeFmt.format(Date(ts)) else dayFmt.format(Date(ts))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageDetailsSheet(
    msg: Message,
    vm: ChatViewModel,
    onOpenProfile: ((String) -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Message details", style = MaterialTheme.typography.titleMedium)
            // For a channel message, surface the sender and let it open their profile.
            if (isChannelPeer(msg.peerHex) && msg.senderHex.isNotBlank()) {
                val senderLabel = msg.senderName.ifBlank { vm.nameForHex(msg.senderHex) }
                Row(
                    Modifier.fillMaxWidth()
                        .then(if (onOpenProfile != null) Modifier.clickable { onOpenProfile(msg.senderHex) } else Modifier),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Sender", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(seed = msg.senderHex, label = senderLabel, size = 28)
                        Spacer(Modifier.size(8.dp))
                        Text(
                            senderLabel,
                            fontWeight = FontWeight.Medium,
                            color = if (onOpenProfile != null) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            DetailRow("Direction", if (msg.incoming) "Incoming" else "Outgoing")
            DetailRow("Time", "${dayFmt.format(Date(msg.timestampMs))} ${formatClock(msg.timestampMs)}")
            if (!msg.incoming) {
                DetailRow("Status", when (msg.status) {
                    MsgStatus.SENDING -> "Sending…"
                    MsgStatus.SENT -> "Sent to mesh"
                    MsgStatus.DELIVERED -> "Delivered (ACK received)"
                    else -> "Failed to send"
                })
            }
            val relays = relayCount(msg.routeHex)
            // Real intermediate relays only — drop the final hop (always this node); the
            // sender/recipient endpoints aren't shown.
            val relayHops = msg.routeHex.split(",").filter { it.isNotBlank() }.dropLast(1)
            DetailRow("Delivery", when {
                msg.routeHex.isBlank() -> if (msg.incoming) "—" else "Awaiting confirmation"
                relays == 0 -> "Direct (no relays)"
                else -> "$relays relay${if (relays == 1) "" else "s"}"
            })
            if (relayHops.isNotEmpty()) {
                Text(
                    "Relays",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                relayHops.forEachIndexed { i, hop ->
                    Text(
                        "${i + 1}. ${vm.nameForHex(hop)}  ·  ${hop.take(8)}",
                        fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Medium)
    }
}

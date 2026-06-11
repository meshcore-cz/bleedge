package cz.arnal.bleedge.chat.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cz.arnal.bleedge.chat.ChannelSummary
import cz.arnal.bleedge.chat.ChatViewModel
import cz.arnal.bleedge.chat.data.channelPeerId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelsScreen(
    vm: ChatViewModel,
    onOpenChannel: (String) -> Unit,
    onOpenProfile: (String) -> Unit,
) {
    val channels by vm.channelConversations.collectAsState()
    var showJoin by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Channels") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showJoin = true }) {
                Icon(Icons.Default.Add, contentDescription = "Join channel")
            }
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        if (channels.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Default.Public, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.size(12.dp))
                Text("No channels", style = MaterialTheme.typography.titleMedium)
                Text("Tap + to join a channel.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(channels, key = { it.pskHex }) { ch ->
                    ChannelRow(
                        ch,
                        onClick = { onOpenChannel(channelPeerId(ch.pskHex)) },
                        onAvatarClick = { onOpenProfile(channelPeerId(ch.pskHex)) },
                    )
                }
            }
        }
    }

    if (showJoin) {
        JoinChannelSheet(
            vm = vm,
            onJoined = { showJoin = false },
            onDismiss = { showJoin = false },
        )
    }
}

@Composable
private fun ChannelRow(ch: ChannelSummary, onClick: () -> Unit, onAvatarClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(seed = ch.pskHex, label = ch.name, onClick = onAvatarClick)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                channelLabel(ch.name, ch.kind),
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val subtitle = when {
                ch.lastText.isBlank() -> "No messages yet"
                ch.lastSender.isBlank() -> ch.lastText
                else -> "${ch.lastSender}: ${ch.lastText}"
            }
            Text(
                subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            if (ch.lastTimestampMs > 0) {
                Text(
                    formatRelative(ch.lastTimestampMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (ch.unread > 0) {
                Spacer(Modifier.size(4.dp))
                Badge { Text("${ch.unread}") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JoinChannelSheet(vm: ChatViewModel, onJoined: () -> Unit, onDismiss: () -> Unit) {
    var namedName by remember { mutableStateOf("") }
    var secretName by remember { mutableStateOf("") }
    var secretValue by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Join channel", style = MaterialTheme.typography.titleMedium)

            // Public
            Text("Public", style = MaterialTheme.typography.labelLarge)
            Text(
                "MeshCore's default public channel (hash 0x11).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = { vm.joinPublic(); onJoined() }, modifier = Modifier.fillMaxWidth()) {
                Text("Join Public")
            }

            HorizontalDivider()

            // Named (public hash, derived from name)
            Text("Named channel", style = MaterialTheme.typography.labelLarge)
            Text(
                "Anyone who knows the name joins the same channel (key = SHA-256(name)).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = namedName,
                onValueChange = { namedName = it },
                label = { Text("Channel name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(
                onClick = { vm.joinNamedChannel(namedName); onJoined() },
                enabled = namedName.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Join named channel") }

            HorizontalDivider()

            // Secret
            Text("Secret channel", style = MaterialTheme.typography.labelLarge)
            Text(
                "Share a secret out-of-band. A 32-hex-char value is used as the raw key; otherwise it's hashed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = secretName,
                onValueChange = { secretName = it },
                label = { Text("Channel name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = secretValue,
                onValueChange = { secretValue = it },
                label = { Text("Secret") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(
                onClick = { vm.joinSecretChannel(secretName, secretValue); onJoined() },
                enabled = secretValue.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Join secret channel") }
        }
    }
}

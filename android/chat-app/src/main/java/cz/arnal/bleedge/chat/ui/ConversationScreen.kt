package cz.arnal.bleedge.chat.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Mood
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cz.arnal.bleedge.chat.ChatViewModel
import cz.arnal.bleedge.chat.MeshCoreUri
import cz.arnal.bleedge.chat.data.Message
import cz.arnal.bleedge.chat.data.channelPskHexOf
import cz.arnal.bleedge.chat.data.isChannelPeer

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ConversationScreen(
    vm: ChatViewModel,
    peerHex: String,
    onBack: (() -> Unit)?,
    onOpenProfile: ((String) -> Unit)? = null,
) {
    val isChannel = isChannelPeer(peerHex)
    val messages by remember(peerHex) { vm.messagesFor(peerHex) }.collectAsState()
    val profile by remember(peerHex) { vm.profileFor(peerHex) }.collectAsState()
    var draft by remember { mutableStateOf("") }
    var detailsFor by remember { mutableStateOf<Message?>(null) }
    var searching by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var showShare by remember { mutableStateOf(false) }
    var showEmoji by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }

    LaunchedEffect(peerHex, messages.size) { vm.markRead(peerHex) }

    // Channel participants (name → node id) learned from the channel's messages; used to
    // resolve a tapped @mention to a profile, and to power the @-autocomplete.
    val mentionTargets = remember(messages) {
        messages.filter { it.senderName.isNotBlank() && it.senderHex.isNotBlank() }
            .associate { it.senderName to it.senderHex }
    }
    val onMentionClick: (String) -> Unit = { name ->
        mentionTargets[name]?.let { hex -> onOpenProfile?.invoke(hex) }
    }

    val mentionQuery = if (isChannel) mentionQueryOf(draft) else null
    val suggestions = if (mentionQuery != null) {
        mentionTargets.keys.filter { it.contains(mentionQuery, ignoreCase = true) }.sorted().take(6)
    } else emptyList()

    val shown = remember(messages, searching, searchQuery) {
        if (searching && searchQuery.isNotBlank()) messages.filter { it.text.contains(searchQuery, ignoreCase = true) }
        else messages
    }

    val shareUri = when {
        isChannel && profile.pskHex.isNotBlank() -> MeshCoreUri.channel(profile.name, profile.pskHex)
        !isChannel && profile.pubKeyHex.isNotBlank() -> MeshCoreUri.contact(profile.name, profile.pubKeyHex)
        else -> null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                title = {
                    if (searching) {
                        TextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            singleLine = true,
                            placeholder = { Text("Search messages") },
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = if (onOpenProfile != null) Modifier.clickable { onOpenProfile(peerHex) } else Modifier,
                        ) {
                            Avatar(seed = peerHex, label = profile.name, size = 36)
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(profile.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                val subtitle = when {
                                    isChannel -> "Channel · shared-key encrypted"
                                    profile.pubKeyHex.isNotBlank() -> formatPubKey(profile.pubKeyHex)
                                    else -> "End-to-end encrypted"
                                }
                                Text(
                                    subtitle,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = if (!isChannel && profile.pubKeyHex.isNotBlank()) FontFamily.Monospace else FontFamily.Default,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                },
                actions = {
                    ConnectionStatusButton(vm)
                    IconButton(onClick = {
                        searching = !searching
                        if (!searching) searchQuery = ""
                    }) {
                        Icon(
                            if (searching) Icons.Default.Close else Icons.Default.Search,
                            contentDescription = if (searching) "Close search" else "Search",
                        )
                    }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(if (isChannel) "Channel info" else "Contact info") },
                            leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) },
                            onClick = { menuOpen = false; onOpenProfile?.invoke(peerHex) },
                        )
                        if (shareUri != null) {
                            DropdownMenuItem(
                                text = { Text(if (isChannel) "Share channel" else "Share contact") },
                                leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                                onClick = { menuOpen = false; showShare = true },
                            )
                        }
                        if (isChannel) {
                            DropdownMenuItem(
                                text = { Text("Leave channel") },
                                leadingIcon = { Icon(Icons.Default.Logout, contentDescription = null) },
                                onClick = { menuOpen = false; confirmLeave = true },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            Column {
                if (suggestions.isNotEmpty()) {
                    MentionSuggestions(suggestions) { name -> draft = insertMention(draft, name) }
                }
                MessageInput(
                    draft = draft,
                    onChange = { draft = it },
                    fullScreen = onBack != null,
                    onEmoji = { showEmoji = true },
                    onSend = {
                        if (draft.isNotBlank()) {
                            if (isChannel) vm.sendChannelMessage(channelPskHexOf(peerHex), draft)
                            else vm.sendChat(peerHex, draft)
                            draft = ""
                        }
                    },
                )
            }
        },
        // TopAppBar + composer handle their own system-bar/ime insets.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        val listState = rememberLazyListState()
        // Jump straight to the newest message on first load (no visible scroll-down), then
        // animate to the bottom only for messages arriving while the chat is open. Skipped
        // while searching so the filtered view doesn't auto-scroll.
        var positioned by remember(peerHex) { mutableStateOf(false) }
        LaunchedEffect(messages.size) {
            if (searching || messages.isEmpty()) return@LaunchedEffect
            if (!positioned) {
                listState.scrollToItem(messages.size - 1)
                positioned = true
            } else {
                listState.animateScrollToItem(messages.size - 1)
            }
        }
        val imeVisible = WindowInsets.isImeVisible
        LaunchedEffect(imeVisible) {
            if (!searching && imeVisible && messages.isNotEmpty() && !listState.canScrollForward) {
                listState.animateScrollToItem(messages.size - 1)
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding),
            // Minimal breathing room at the top and bottom of the message list.
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(shown, key = { it.id }) { msg ->
                val sender = if (isChannel) msg.senderName.ifBlank { vm.nameForHex(msg.senderHex) }
                else vm.nameForHex(msg.senderHex)
                MessageBubble(msg, isChannel, sender, onMentionClick) { detailsFor = msg }
            }
        }
    }

    detailsFor?.let { msg ->
        MessageDetailsSheet(msg, vm, onOpenProfile = onOpenProfile) { detailsFor = null }
    }
    if (showShare && shareUri != null) {
        ShareQrSheet(
            title = if (isChannel) "Share ${channelLabel(profile.name, profile.channelKind)}" else "Share ${profile.name}",
            subtitle = if (isChannel) "Scan to join this channel" else "Scan to add this contact",
            uri = shareUri,
            onDismiss = { showShare = false },
        )
    }
    if (showEmoji) {
        EmojiPickerSheet(onPick = { draft += it }, onDismiss = { showEmoji = false })
    }
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Leave channel?") },
            text = { Text("You'll stop receiving messages on ${channelLabel(profile.name, profile.channelKind)}. You can rejoin later.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmLeave = false
                    vm.leaveChannel(profile.pskHex)
                    onBack?.invoke()
                }) { Text("Leave") }
            },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun MessageBubble(
    msg: Message,
    isChannel: Boolean,
    senderLabel: String,
    onMentionClick: (String) -> Unit,
    onClick: () -> Unit,
) {
    val mine = !msg.incoming
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = if (mine) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.widthIn(max = 300.dp).clickable(onClick = onClick),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (isChannel && msg.incoming) {
                    Text(
                        senderLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                MessageContent(msg.text, enableMentions = isChannel, onMentionClick = onMentionClick)
                Spacer(Modifier.size(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        formatClock(msg.timestampMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (mine) DeliveryTick(msg.status) else RouteIndicator(msg.routeHex)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun MessageInput(
    draft: String,
    onChange: (String) -> Unit,
    fullScreen: Boolean,
    onEmoji: () -> Unit,
    onSend: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    Surface(tonalElevation = 2.dp) {
        Row(
            Modifier.fillMaxWidth()
                .then(if (fullScreen) Modifier.navigationBarsPadding() else Modifier)
                .imePadding()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onEmoji) {
                Icon(Icons.Default.Mood, contentDescription = "Emoji")
            }
            OutlinedTextField(
                value = draft,
                onValueChange = onChange,
                modifier = Modifier.weight(1f).onPreviewKeyEvent { e ->
                    // Enter sends; Ctrl+Enter inserts a newline (handy on hardware keyboards).
                    if (e.type == KeyEventType.KeyDown && e.key == Key.Enter) {
                        if (e.isCtrlPressed) { onChange(draft + "\n"); true } else { onSend(); true }
                    } else false
                },
                placeholder = { Text("Message") },
                maxLines = 5,
                // Highlight @[mentions] as they're typed; the stored text is unchanged.
                visualTransformation = mentionInputTransformation(accent),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
            )
            IconButton(onClick = onSend, enabled = draft.isNotBlank()) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }
    }
}

/** Horizontal strip of @-mention candidates shown above the composer while typing "@". */
@Composable
private fun MentionSuggestions(names: List<String>, onPick: (String) -> Unit) {
    Surface(tonalElevation = 3.dp) {
        LazyRow(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(names, key = { it }) { name ->
                AssistChip(onClick = { onPick(name) }, label = { Text("@$name") })
            }
        }
    }
}

/** The active mention query: the partial after a trailing "@" (word-boundary), or null. */
fun mentionQueryOf(draft: String): String? =
    Regex("""(?:^|\s)@([^\s@]*)$""").find(draft)?.groupValues?.get(1)

/** Replaces the trailing "@partial" the user is typing with a `@[Name]` token (and a space). */
fun insertMention(draft: String, name: String): String =
    Regex("""(?:^|\s)@([^\s@]*)$""").replace(draft) { m ->
        val lead = if (m.value.startsWith("@")) "" else m.value.take(1)
        "$lead@[$name] "
    }

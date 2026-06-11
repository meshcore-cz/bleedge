package cz.arnal.bleedge.chat.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import cz.arnal.bleedge.chat.ChatViewModel

@Composable
fun ChatRoot(vm: ChatViewModel) {
    var openPeer by rememberSaveable { mutableStateOf<String?>(null) }
    var openProfile by rememberSaveable { mutableStateOf<String?>(null) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var tab by rememberSaveable { mutableStateOf(0) }

    // A profile page / direct conversation / settings are shown full-screen over the tabs.
    if (openProfile != null) {
        ProfileScreen(
            vm, openProfile!!,
            onBack = { openProfile = null },
            onOpenConversation = { openProfile = null; openPeer = it },
        )
        return
    }
    if (openPeer != null) {
        ConversationScreen(
            vm, openPeer!!,
            onBack = { openPeer = null },
            onOpenProfile = { openProfile = it },
        )
        return
    }
    if (showSettings) {
        SettingsScreen(vm, onBack = { showSettings = false })
        return
    }

    val conversations by vm.conversations.collectAsState()
    val unread = remember(conversations) { conversations.sumOf { it.unread } }

    Scaffold(
        // Each tab screen owns its own system-bar insets via its TopAppBar / composer,
        // so the root must not also pad the content (that double-counted the status bar).
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            // The tab bar is always shown. Full-screen views (conversation, profile, settings)
            // render outside this scaffold via early return, so the only keyboard that ever
            // coexists with the bar is the Chats search field — keeping the bar visible there
            // is fine, and tying it to IME-inset detection broke on some Android 13 devices.
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = {
                        BadgedBox(badge = { if (unread > 0) Badge { Text("$unread") } }) {
                            Icon(Icons.Default.Forum, contentDescription = "Chats")
                        }
                    },
                    label = { Text("Chats") },
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.Default.Public, contentDescription = "Channels") },
                    label = { Text("Channels") },
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = { Icon(Icons.Default.Hub, contentDescription = "Network") },
                    label = { Text("Network") },
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                0 -> ChatsScreen(
                    vm,
                    onOpenConversation = { openPeer = it },
                    onOpenProfile = { openProfile = it },
                    onOpenSettings = { showSettings = true },
                )
                1 -> ChannelsScreen(
                    vm,
                    onOpenChannel = { openPeer = it },
                    onOpenProfile = { openProfile = it },
                )
                else -> NetworkScreen(vm, onOpenProfile = { openProfile = it })
            }
        }
    }
}

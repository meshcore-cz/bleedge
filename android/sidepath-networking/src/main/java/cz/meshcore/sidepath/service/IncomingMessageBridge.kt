package cz.meshcore.sidepath.service

/**
 * A generic, app-agnostic hand-off for received messages. The networking service has no knowledge of
 * the app's contacts, channels, mute settings or notification UI — it just forwards every decrypted
 * [ReceivedMessage] to a [listener] the host app registers (typically at Application startup).
 *
 * This exists so chat notifications can be posted by the app even when its UI (Activity/ViewModel) has
 * been destroyed: the foreground service keeps running and keeps calling [listener], so the
 * always-resident app object can still resolve and post. All policy/rendering stays in the app.
 */
object IncomingMessageBridge {
    fun interface Listener {
        fun onIncoming(msg: ReceivedMessage)
    }

    @Volatile
    var listener: Listener? = null
}

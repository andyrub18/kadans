package app.kadans.realtime

import app.kadans.api.KadansApi
import app.kadans.api.KadansJson
import app.kadans.api.model.NotificationResponse
import app.kadans.api.model.PomodoroRunResponse
import app.kadans.reminders.LocalReminders
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

sealed interface RealtimeEvent {
    data class NotificationReceived(val notification: NotificationResponse) : RealtimeEvent

    data class PomodoroRunChanged(val run: PomodoroRunResponse) : RealtimeEvent

    /** The account's reminders changed (here or on another device): a device that rings them fetches its window again. */
    data object RemindersChanged : RealtimeEvent

    /** Connected again after a drop: whatever was signalled meanwhile was lost (notifications are caught up from the list). */
    data object Reconnected : RealtimeEvent
}

/**
 * Live connection to the server's SignalR hub. Best-effort by design: the REST API stays the
 * source of truth, this only makes changes arrive without a refresh. Reconnects with backoff
 * for as long as [start] is in effect; [stop] on sign-out. The server closes a connection when
 * its access token expires (hourly) or its session ends; after a reconnect, the notifications
 * that arrived during the gap are delivered from the notification list, so a reminder never
 * falls into it (the desktop app has no push to cover for it).
 */
class KadansRealtime(private val api: KadansApi) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var connection: Job? = null

    private val _events = MutableSharedFlow<RealtimeEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<RealtimeEvent> = _events.asSharedFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    /** What one [start] remembers across its reconnects; owned by its connection loop alone. */
    private class Run {
        /** When the last established connection dropped; null before the first one (nothing to catch up). */
        var droppedAt: Instant? = null

        /** Notifications already delivered, newest last: a catch-up never repeats a live one. */
        val delivered = LinkedHashSet<String>()
    }

    fun start() {
        if (connection?.isActive == true) return
        connection = scope.launch { connectLoop() }
    }

    fun stop() {
        connection?.cancel()
        connection = null
        _connected.value = false
    }

    private suspend fun connectLoop() {
        val run = Run() // the next start may be another account: nothing of this one carries over
        var attempt = 0
        while (currentCoroutineContextIsActive()) {
            val token = api.tokenStore.load()?.accessToken
            if (token == null) {
                delay(3_000)
                continue
            }
            try {
                connectOnce(token, run)
                attempt = 0 // a session that established then dropped restarts the backoff
            } catch (_: Exception) {
                // Likely an expired access token: any authed REST call refreshes the pair.
                runCatching { api.notifications.unreadCount() }
            } finally {
                if (_connected.value) run.droppedAt = Clock.System.now()
                _connected.value = false
            }
            attempt += 1
            delay(backoffMillis(attempt))
        }
    }

    private suspend fun connectOnce(token: String, run: Run) {
        val url = api.baseUrl
            .replaceFirst("http://", "ws://")
            .replaceFirst("https://", "wss://") +
            "/hubs/kadans?access_token=$token"
        api.http.webSocket(url) {
            send(Frame.Text(SignalRProtocol.HANDSHAKE))
            _connected.value = true
            // Before reading live frames (they wait in the channel), so both paths run one after the other.
            run.droppedAt?.let {
                catchUp(since = it, run)
                _events.tryEmit(RealtimeEvent.Reconnected)
            }
            val buffer = StringBuilder()
            val keepAlive = launch {
                while (true) {
                    delay(15_000)
                    send(Frame.Text(SignalRProtocol.PING))
                }
            }
            try {
                while (true) {
                    // The server pings every 15 s. Nothing for 30 s: the connection is gone, though the socket may not
                    // know it (Android froze the app in the background, the network changed under it). Reconnect.
                    val received = withTimeoutOrNull(SERVER_TIMEOUT) { incoming.receiveCatching() } ?: return@webSocket
                    val frame = received.getOrNull() ?: break
                    val text = (frame as? Frame.Text)?.readText() ?: continue
                    for (raw in SignalRProtocol.extractFrames(buffer, text)) {
                        when (val message = SignalRProtocol.parse(raw)) {
                            is SignalRProtocol.Message.Invocation -> dispatch(message, run)
                            is SignalRProtocol.Message.Close -> return@webSocket
                            else -> {}
                        }
                    }
                }
            } finally {
                keepAlive.cancel()
            }
        }
    }

    private suspend fun catchUp(since: Instant, run: Run) {
        val unread = runCatching { api.notifications.list(unreadOnly = true, page = 1, pageSize = CATCH_UP_PAGE) }.getOrNull() ?: return
        missedSince(unread, since, run.delivered).forEach { deliver(it, run) }
    }

    private fun deliver(notification: NotificationResponse, run: Run) {
        if (!run.delivered.add(notification.id)) return
        if (run.delivered.size > REMEMBERED) run.delivered.remove(run.delivered.first())
        _events.tryEmit(RealtimeEvent.NotificationReceived(notification))
    }

    private fun dispatch(message: SignalRProtocol.Message.Invocation, run: Run) {
        val payload = message.arguments.firstOrNull() ?: return
        val event = try {
            when (message.target) {
                "notification" ->
                    RealtimeEvent.NotificationReceived(
                        KadansJson.decodeFromJsonElement(NotificationResponse.serializer(), payload)
                    )
                "pomodoro.run.changed" ->
                    RealtimeEvent.PomodoroRunChanged(
                        KadansJson.decodeFromJsonElement(PomodoroRunResponse.serializer(), payload)
                    )
                LocalReminders.CHANGED_KIND -> RealtimeEvent.RemindersChanged
                else -> null
            }
        } catch (_: Exception) {
            null
        }
        when (event) {
            is RealtimeEvent.NotificationReceived -> deliver(event.notification, run)
            null -> {}
            else -> _events.tryEmit(event)
        }
    }

    internal companion object {
        private const val CATCH_UP_PAGE = 50
        private const val REMEMBERED = 200

        /** The device's clock may differ from the server's: notifications this much older than the drop count too. */
        internal val CLOCK_MARGIN = 2.minutes

        /** Twice the server's keep-alive (SignalR's 15 s), as SignalR's own clients wait. */
        internal val SERVER_TIMEOUT = 30.seconds

        /** Unread notifications from around the drop on, oldest first, minus those already shown. */
        internal fun missedSince(unread: List<NotificationResponse>, since: Instant, delivered: Set<String>): List<NotificationResponse> =
            unread.filter { it.createdAt >= since - CLOCK_MARGIN && it.id !in delivered }.sortedBy { it.createdAt }

        fun backoffMillis(attempt: Int): Long = when {
            attempt <= 1 -> 1_000
            attempt == 2 -> 2_000
            attempt == 3 -> 5_000
            attempt == 4 -> 10_000
            else -> 30_000
        }
    }
}

private suspend fun currentCoroutineContextIsActive(): Boolean =
    kotlinx.coroutines.currentCoroutineContext().isActive

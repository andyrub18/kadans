package app.kadans.realtime

import app.kadans.api.KadansApi
import app.kadans.api.KadansJson
import app.kadans.api.model.NotificationResponse
import app.kadans.api.model.PomodoroRunResponse
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

sealed interface RealtimeEvent {
    data class NotificationReceived(val notification: NotificationResponse) : RealtimeEvent

    data class PomodoroRunChanged(val run: PomodoroRunResponse) : RealtimeEvent
}

/**
 * Live connection to the server's SignalR hub. Best-effort by design: the REST API stays the
 * source of truth, this only makes changes arrive without a refresh. Reconnects with backoff
 * for as long as [start] is in effect; [stop] on sign-out.
 */
class KadansRealtime(private val api: KadansApi) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var connection: Job? = null

    private val _events = MutableSharedFlow<RealtimeEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<RealtimeEvent> = _events.asSharedFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

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
        var attempt = 0
        while (currentCoroutineContextIsActive()) {
            val token = api.tokenStore.load()?.accessToken
            if (token == null) {
                delay(3_000)
                continue
            }
            try {
                connectOnce(token)
                attempt = 0 // a session that established then dropped restarts the backoff
            } catch (_: Exception) {
                // Likely an expired access token: any authed REST call refreshes the pair.
                runCatching { api.notifications.unreadCount() }
            } finally {
                _connected.value = false
            }
            attempt += 1
            delay(backoffMillis(attempt))
        }
    }

    private suspend fun connectOnce(token: String) {
        val url = api.baseUrl
            .replaceFirst("http://", "ws://")
            .replaceFirst("https://", "wss://") +
            "/hubs/kadans?access_token=$token"
        api.http.webSocket(url) {
            send(Frame.Text(SignalRProtocol.HANDSHAKE))
            _connected.value = true
            val buffer = StringBuilder()
            val keepAlive = launch {
                while (true) {
                    delay(15_000)
                    send(Frame.Text(SignalRProtocol.PING))
                }
            }
            try {
                for (frame in incoming) {
                    val text = (frame as? Frame.Text)?.readText() ?: continue
                    for (raw in SignalRProtocol.extractFrames(buffer, text)) {
                        when (val message = SignalRProtocol.parse(raw)) {
                            is SignalRProtocol.Message.Invocation -> dispatch(message)
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

    private fun dispatch(message: SignalRProtocol.Message.Invocation) {
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
                else -> null
            }
        } catch (_: Exception) {
            null
        }
        if (event != null) _events.tryEmit(event)
    }

    private companion object {
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

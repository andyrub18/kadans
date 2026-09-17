package app.kadans.ui.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kadans.api.KadansApi
import app.kadans.api.KadansApiException
import app.kadans.api.model.NotificationResponse
import app.kadans.realtime.RealtimeEvent
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

sealed interface NotificationsUiState {
    data object Loading : NotificationsUiState

    data class Content(
        val items: List<NotificationResponse>,
        val hasMore: Boolean,
        val isLoadingMore: Boolean = false,
    ) : NotificationsUiState {
        val unread: Int get() = items.count { it.readAt == null }
    }

    data class Error(val message: String?, val code: String? = null) : NotificationsUiState
}

/**
 * The notification centre: everything the server ever told this user (reminders, hands-free phase
 * changes), newest first. Reading is optimistic — the row turns read at once and the server is
 * told behind it; a failed call resyncs from the server instead of lying.
 */
class NotificationsViewModel(
    private val api: KadansApi,
    events: Flow<RealtimeEvent>,
) : ViewModel() {
    private val _state = MutableStateFlow<NotificationsUiState>(NotificationsUiState.Loading)
    val state: StateFlow<NotificationsUiState> = _state.asStateFlow()

    init {
        // A notification arriving while the list is open belongs at its top.
        viewModelScope.launch {
            events.collect { event ->
                val current = _state.value
                if (event is RealtimeEvent.NotificationReceived && current is NotificationsUiState.Content &&
                    current.items.none { it.id == event.notification.id }
                ) {
                    _state.value = current.copy(items = listOf(event.notification) + current.items)
                }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            try {
                val page = api.notifications.list(page = 1, pageSize = PAGE_SIZE)
                _state.value = NotificationsUiState.Content(page, hasMore = page.size == PAGE_SIZE)
            } catch (e: KadansApiException) {
                _state.value = NotificationsUiState.Error(e.message, e.errorCode)
            } catch (e: Exception) {
                _state.value = NotificationsUiState.Error(null, "network")
            }
        }
    }

    fun loadMore() {
        val current = _state.value as? NotificationsUiState.Content ?: return
        if (!current.hasMore || current.isLoadingMore) return
        _state.value = current.copy(isLoadingMore = true)
        viewModelScope.launch {
            try {
                // Pages are positional; live arrivals shift them, so drop what we already show.
                val next = api.notifications.list(page = current.items.size / PAGE_SIZE + 1, pageSize = PAGE_SIZE)
                val latest = _state.value as? NotificationsUiState.Content ?: return@launch
                val known = latest.items.mapTo(HashSet()) { it.id }
                _state.value = latest.copy(
                    items = latest.items + next.filter { it.id !in known },
                    hasMore = next.size == PAGE_SIZE,
                    isLoadingMore = false,
                )
            } catch (e: Exception) {
                (_state.value as? NotificationsUiState.Content)?.let { _state.value = it.copy(isLoadingMore = false) }
            }
        }
    }

    fun markRead(id: String) {
        val current = _state.value as? NotificationsUiState.Content ?: return
        if (current.items.none { it.id == id && it.readAt == null }) return
        val now = Clock.System.now()
        _state.value = current.copy(items = current.items.map { if (it.id == id) it.copy(readAt = now) else it })
        viewModelScope.launch {
            try {
                api.notifications.markRead(id)
            } catch (e: Exception) {
                refresh()
            }
        }
    }

    fun markAllRead() {
        val current = _state.value as? NotificationsUiState.Content ?: return
        if (current.unread == 0) return
        val now = Clock.System.now()
        _state.value = current.copy(items = current.items.map { if (it.readAt == null) it.copy(readAt = now) else it })
        viewModelScope.launch {
            try {
                api.notifications.markAllRead()
            } catch (e: Exception) {
                refresh()
            }
        }
    }

    internal companion object {
        const val PAGE_SIZE = 30

        /** `2026-09-17 14:05` in the device's zone — sortable, unambiguous in all three languages. */
        fun formatTimestamp(at: Instant, timeZone: TimeZone): String {
            val local = at.toLocalDateTime(timeZone)
            fun two(value: Int) = value.toString().padStart(2, '0')
            return "${local.year}-${two(local.month.ordinal + 1)}-${two(local.day)} ${two(local.hour)}:${two(local.minute)}"
        }
    }
}

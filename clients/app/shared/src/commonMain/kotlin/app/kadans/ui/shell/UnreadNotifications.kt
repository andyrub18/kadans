package app.kadans.ui.shell

import app.kadans.api.KadansApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The bell's count, the same on every destination's bar: the server's number, raised at once when a notification
 * arrives live (Home hears the hub), asked again when a destination comes on screen (back from the list, which
 * marks them read).
 */
class UnreadNotifications(private val api: KadansApi) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _count = MutableStateFlow(0)
    val count: StateFlow<Int> = _count.asStateFlow()

    /** The server's count; offline or signed out, what is shown stays. */
    fun refresh() {
        scope.launch { runCatching { api.notifications.unreadCount() }.onSuccess { _count.value = it } }
    }

    fun set(count: Int) {
        _count.value = count
    }

    /** One just arrived: shown before the server is asked. */
    fun arrived() {
        _count.value += 1
    }

    companion object {
        /** A badge must stay a badge: past 99 the exact number stops mattering. */
        fun badgeText(unread: Int): String = if (unread > 99) "99+" else unread.toString()
    }
}

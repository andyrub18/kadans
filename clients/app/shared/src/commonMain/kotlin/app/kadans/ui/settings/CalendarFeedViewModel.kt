package app.kadans.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kadans.api.KadansApi
import app.kadans.api.KadansApiException
import app.kadans.api.model.CalendarFeedResponse
import app.kadans.sync.DataArea
import app.kadans.sync.DataChanges
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** [feed]: null until the first answer. [isBusy]: turning on, replacing the link or turning off. */
data class CalendarFeedUiState(
    val feed: CalendarFeedResponse? = null,
    val isLoading: Boolean = true,
    val isBusy: Boolean = false,
    val error: String? = null,
    val errorCode: String? = null,
)

/** Settings → My todos in my calendar: the account's calendar feed (ARCHITECTURE → "The calendar feed"). */
class CalendarFeedViewModel(private val api: KadansApi, private val dataChanges: DataChanges) : ViewModel() {
    private val _state = MutableStateFlow(CalendarFeedUiState())
    val state: StateFlow<CalendarFeedUiState> = _state.asStateFlow()

    init {
        load()
        // Turned on, off or replaced on another device (the server then says the todos' area changed), or the app
        // back in front, when a calendar may have read the link meanwhile: shown as it is now.
        viewModelScope.launch { dataChanges.of(DataArea.Todos).collect { load(quiet = true) } }
    }

    /** [quiet]: keeps what is shown while asking, and through a failure (the next signal asks again). */
    fun load(quiet: Boolean = false) {
        viewModelScope.launch {
            if (!quiet) _state.update { it.copy(isLoading = true, error = null, errorCode = null) }
            try {
                val feed = api.calendarFeed.get()
                _state.update { it.copy(feed = feed, isLoading = false) }
            } catch (e: KadansApiException) {
                if (!quiet) _state.update { it.copy(isLoading = false, error = e.message, errorCode = e.errorCode) }
            } catch (e: Exception) {
                if (!quiet) _state.update { it.copy(isLoading = false, error = null, errorCode = "network") }
            }
        }
    }

    fun turnOn() = change { api.calendarFeed.turnOn() }

    /** A new link; the calendars that followed the old one stop until they are given this one. */
    fun replaceLink() = change { api.calendarFeed.reset() }

    fun turnOff() = change {
        api.calendarFeed.turnOff()
        CalendarFeedResponse(enabled = false)
    }

    private fun change(action: suspend () -> CalendarFeedResponse) {
        if (_state.value.isBusy) return
        _state.update { it.copy(isBusy = true, error = null, errorCode = null) }
        viewModelScope.launch {
            try {
                val feed = action()
                _state.update { it.copy(feed = feed, isBusy = false) }
            } catch (e: KadansApiException) {
                _state.update { it.copy(isBusy = false, error = e.message, errorCode = e.errorCode) }
            } catch (e: Exception) {
                _state.update { it.copy(isBusy = false, error = null, errorCode = "network") }
            }
        }
    }
}

package app.kadans.ui.auth

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kadans.api.KadansApi
import app.kadans.api.KadansApiException
import app.kadans.i18n.LocalStrings
import kotlin.time.Instant
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

data class KeepAccountUiState(val isLoading: Boolean = false, val error: String? = null, val errorCode: String? = null)

/** A sign-in into an account awaiting erasure: keep it (a session starts), or leave it closed. */
class KeepAccountViewModel(private val api: KadansApi, private val restoreToken: String) : ViewModel() {
    private val _state = MutableStateFlow(KeepAccountUiState())
    val state: StateFlow<KeepAccountUiState> = _state.asStateFlow()

    private val _kept = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val kept: SharedFlow<Unit> = _kept.asSharedFlow()

    fun keep() {
        _state.update { it.copy(isLoading = true, error = null, errorCode = null) }
        viewModelScope.launch {
            try {
                api.auth.restoreAccount(restoreToken)
                _state.update { it.copy(isLoading = false) }
                _kept.emit(Unit)
            } catch (e: KadansApiException) {
                _state.update { it.copy(isLoading = false, error = e.message, errorCode = e.errorCode) }
            } catch (_: Exception) {
                _state.update { it.copy(isLoading = false, errorCode = "network") }
            }
        }
    }
}

@Composable
fun KeepAccountScreen(
    eraseAfter: Instant,
    restoreToken: String,
    onKept: () -> Unit,
    onLeave: () -> Unit,
    viewModel: KeepAccountViewModel = koinViewModel(key = "keep-$restoreToken") { parametersOf(restoreToken) },
) {
    val state by viewModel.state.collectAsState()
    val s = LocalStrings.current
    val d = s.deletion
    LaunchedEffect(viewModel) { viewModel.kept.collect { onKept() } }

    AuthScaffold(title = d.keepTitle) {
        Text(d.keepText(d.date(eraseAfter.toLocalDateTime(TimeZone.currentSystemDefault()).date)), style = MaterialTheme.typography.bodyLarge)
        if (state.error != null || state.errorCode != null) {
            Text(s.errorFor(state.errorCode, state.error), color = MaterialTheme.colorScheme.error)
        }
        Button(onClick = viewModel::keep, enabled = !state.isLoading, modifier = Modifier.fillMaxWidth()) { Text(d.keepButton) }
        TextButton(onClick = onLeave, modifier = Modifier.fillMaxWidth()) { Text(d.notNow) }
    }
}

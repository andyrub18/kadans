package app.kadans.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kadans.api.KadansApi
import app.kadans.api.KadansApiException
import app.kadans.files.FilePorts
import app.kadans.files.SaveResult
import app.kadans.i18n.LocalStrings
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import org.koin.compose.viewmodel.koinViewModel

/** [isBusy]: the copy is being made, or the person is choosing where it goes. */
data class DataExportUiState(
    val isBusy: Boolean = false,
    val saved: Boolean = false,
    /** The place chosen could not take the file. */
    val saveFailed: Boolean = false,
    val error: String? = null,
    val errorCode: String? = null,
)

/**
 * Settings → My data (ARCHITECTURE → "Statements in, data out"): the server makes one zip of what the person put in
 * Kadans, and the system's own "Save" dialog puts it where they choose. The copy is asked for first and the place
 * second, so a place is never left holding an empty file. Nothing of it stays in the app.
 */
class DataExportViewModel(
    private val api: KadansApi,
    private val files: FilePorts,
    private val today: () -> LocalDate = { Clock.System.todayIn(TimeZone.currentSystemDefault()) },
) : ViewModel() {
    private val _state = MutableStateFlow(DataExportUiState())
    val state: StateFlow<DataExportUiState> = _state.asStateFlow()

    fun save(dialogTitle: String) {
        if (_state.value.isBusy) return
        _state.value = DataExportUiState(isBusy = true)
        viewModelScope.launch {
            try {
                val archive = api.account.export()
                val result = files.save(dialogTitle, fileName(today()), "application/zip", archive)
                _state.update { it.copy(isBusy = false, saved = result == SaveResult.Saved, saveFailed = result == SaveResult.Failed) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: KadansApiException) {
                _state.update { it.copy(isBusy = false, error = e.message, errorCode = e.errorCode) }
            } catch (_: Exception) {
                _state.update { it.copy(isBusy = false, error = null, errorCode = "network") }
            }
        }
    }

    internal companion object {
        /** Named by the app, with the day it was made: nothing a server says names a file on the person's device. */
        fun fileName(day: LocalDate): String = "kadans-export-$day.zip"
    }
}

@Composable
internal fun DataExportPage(viewModel: DataExportViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsState()
    val s = LocalStrings.current
    val d = s.dataExport

    Text(d.intro, style = MaterialTheme.typography.bodyMedium)
    Text(d.contents, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Button(onClick = { viewModel.save(d.fileDialogTitle) }, enabled = !state.isBusy, modifier = Modifier.fillMaxWidth()) { Text(d.save) }
    when {
        state.isBusy -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(d.preparing, style = MaterialTheme.typography.bodySmall)
        }
        state.saved -> Text(d.saved, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
        state.saveFailed -> Text(d.failed, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        state.error != null || state.errorCode != null ->
            Text(s.errorFor(state.errorCode, state.error), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    Text(d.importHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

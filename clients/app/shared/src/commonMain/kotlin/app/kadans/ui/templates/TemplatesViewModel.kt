package app.kadans.ui.templates

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kadans.api.KadansApi
import app.kadans.api.KadansApiException
import app.kadans.api.model.CreatePomodoroPhase
import app.kadans.api.model.CreatePomodoroTemplate
import app.kadans.api.model.PomodoroPhaseType
import app.kadans.api.model.PomodoroTemplateResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface TemplatesUiState {
    data object Loading : TemplatesUiState

    data class Content(val templates: List<PomodoroTemplateResponse>) : TemplatesUiState

    data class Error(val message: String) : TemplatesUiState
}

/** null id = creating a new template. */
data class TemplateEditorState(
    val id: String? = null,
    val name: String = "",
    val phases: List<CreatePomodoroPhase> = listOf(
        CreatePomodoroPhase(PomodoroPhaseType.Focus, 25),
        CreatePomodoroPhase(PomodoroPhaseType.Break, 5),
    ),
    val isSaving: Boolean = false,
    val error: String? = null,
) {
    val canSave: Boolean
        get() = name.isNotBlank() && phases.isNotEmpty() && phases.all { it.durationMinutes > 0 } && !isSaving
}

class TemplatesViewModel(private val api: KadansApi) : ViewModel() {
    private val _state = MutableStateFlow<TemplatesUiState>(TemplatesUiState.Loading)
    val state: StateFlow<TemplatesUiState> = _state.asStateFlow()

    private val _editor = MutableStateFlow<TemplateEditorState?>(null)
    val editor: StateFlow<TemplateEditorState?> = _editor.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            try {
                _state.value = TemplatesUiState.Content(api.pomodoro.templates())
            } catch (e: KadansApiException) {
                _state.value = TemplatesUiState.Error(e.message ?: "Request failed")
            } catch (e: Exception) {
                _state.value = TemplatesUiState.Error("Could not reach the server.")
            }
        }
    }

    fun openNew() {
        _editor.value = TemplateEditorState()
    }

    fun openEdit(template: PomodoroTemplateResponse) {
        _editor.value = TemplateEditorState(
            id = template.id,
            name = template.name,
            phases = template.phases.sortedBy { it.order }.map { CreatePomodoroPhase(it.type, it.durationMinutes) },
        )
    }

    fun closeEditor() {
        _editor.value = null
    }

    fun updateEditor(transform: (TemplateEditorState) -> TemplateEditorState) =
        _editor.update { it?.let { current -> transform(current).copy(error = null) } }

    fun save() {
        val current = _editor.value ?: return
        if (!current.canSave) return

        _editor.update { it?.copy(isSaving = true, error = null) }
        viewModelScope.launch {
            try {
                val request = CreatePomodoroTemplate(current.name.trim(), current.phases)
                if (current.id == null) api.pomodoro.createTemplate(request)
                else api.pomodoro.updateTemplate(current.id, request)
                _editor.value = null
                refresh()
            } catch (e: KadansApiException) {
                _editor.update { it?.copy(isSaving = false, error = e.message) }
            } catch (e: Exception) {
                _editor.update { it?.copy(isSaving = false, error = "Could not reach the server.") }
            }
        }
    }

    fun delete(templateId: String) {
        viewModelScope.launch {
            try {
                api.pomodoro.deleteTemplate(templateId)
                _editor.value = null
                refresh()
            } catch (e: KadansApiException) {
                _editor.update { it?.copy(error = e.message) }
            } catch (e: Exception) {
                _editor.update { it?.copy(error = "Could not reach the server.") }
            }
        }
    }
}

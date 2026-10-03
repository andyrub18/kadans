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

    data class Error(val message: String?, val code: String? = null) : TemplatesUiState
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
    val errorCode: String? = null,
) {
    /** The server's limits: a cycle of 1 to 24 phases, each 1 to 240 minutes. */
    val withinLimits: Boolean
        get() = phases.size in 1..MAX_PHASES && phases.all { it.durationMinutes in 1..MAX_MINUTES }

    val canSave: Boolean
        get() = name.isNotBlank() && withinLimits && !isSaving

    companion object {
        const val MAX_PHASES = 24
        const val MAX_MINUTES = 240

        /**
         * The classic pomodoro as phases: [rounds] focuses with a short break after each, the last break long
         * ("15 focus + 5 break, 4 times, then 30"). No long break ([longBreak] 0) keeps the last break short.
         */
        fun classicCycle(focus: Int, shortBreak: Int, rounds: Int, longBreak: Int): List<CreatePomodoroPhase> =
            (1..rounds).flatMap { round ->
                listOf(
                    CreatePomodoroPhase(PomodoroPhaseType.Focus, focus),
                    CreatePomodoroPhase(PomodoroPhaseType.Break, if (round == rounds && longBreak > 0) longBreak else shortBreak),
                )
            }
    }
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
                _state.value = TemplatesUiState.Error(e.message, e.errorCode)
            } catch (e: Exception) {
                _state.value = TemplatesUiState.Error(null, "network")
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
        _editor.update { it?.let { current -> transform(current).copy(error = null, errorCode = null) } }

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
                _editor.update { it?.copy(isSaving = false, error = e.message, errorCode = e.errorCode) }
            } catch (e: Exception) {
                _editor.update { it?.copy(isSaving = false, error = null, errorCode = "network") }
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
                _editor.update { it?.copy(error = e.message, errorCode = e.errorCode) }
            } catch (e: Exception) {
                _editor.update { it?.copy(error = null, errorCode = "network") }
            }
        }
    }
}

@file:UseSerializers(IsoInstantSerializer::class)

package app.kadans.api.model

import app.kadans.api.IsoInstantSerializer
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

@Serializable
enum class PomodoroPhaseType { Focus, Break }

@Serializable
enum class PomodoroRunStatus { Active, Paused, Completed, Cancelled }

@Serializable
data class CreatePomodoroPhase(val type: PomodoroPhaseType, val durationMinutes: Int)

@Serializable
data class CreatePomodoroTemplate(val name: String, val phases: List<CreatePomodoroPhase>)

@Serializable
data class UpdateTodoPomodoro(val pomodoroTemplateId: String? = null)

@Serializable
data class AdvancePomodoroRun(val expectedPhaseIndex: Int? = null)

@Serializable
data class PomodoroPhaseResponse(
    val id: String,
    val order: Int,
    val type: PomodoroPhaseType,
    val durationMinutes: Int,
)

@Serializable
data class PomodoroTemplateResponse(
    val id: String,
    val name: String,
    val phases: List<PomodoroPhaseResponse> = emptyList(),
    val createdAt: Instant,
    val updatedAt: Instant,
)

@Serializable
data class PomodoroRunPhaseResponse(
    val id: String,
    val order: Int,
    val type: PomodoroPhaseType,
    val durationMinutes: Int,
    val startedAt: Instant? = null,
    val completedAt: Instant? = null,
)

/** Active: count down to [phaseEndsAt]. Paused: [pausedRemainingSeconds] is what is left. */
@Serializable
data class PomodoroRunResponse(
    val id: String,
    val todoId: String,
    val pomodoroTemplateId: String? = null,
    val status: PomodoroRunStatus,
    val currentPhaseIndex: Int,
    val phaseEndsAt: Instant? = null,
    val pausedRemainingSeconds: Int? = null,
    val autoAdvance: Boolean = false,
    val phases: List<PomodoroRunPhaseResponse> = emptyList(),
    val startedAt: Instant,
    val pausedAt: Instant? = null,
    val completedAt: Instant? = null,
    val updatedAt: Instant,
)

@Serializable
data class PomodoroDayStats(
    val date: LocalDate,
    val focusMinutes: Int,
    val breakMinutes: Int,
    val completedRuns: Int,
)

@Serializable
data class PomodoroStatsResponse(
    val from: Instant,
    val to: Instant,
    val timeZoneId: String,
    val completedRuns: Int,
    val cancelledRuns: Int,
    val focusMinutes: Int,
    val breakMinutes: Int,
    val perDay: List<PomodoroDayStats> = emptyList(),
)

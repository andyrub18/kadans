@file:UseSerializers(IsoInstantSerializer::class)

package app.kadans.api.model

import app.kadans.api.IsoInstantSerializer
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

@Serializable
enum class TaskStatus { Scheduled, Started, Completed, Cancelled }

@Serializable
enum class OccurrenceStatus { Pending, Completed, Cancelled }

@Serializable
enum class Frequency { Minutely, Hourly, Daily, Weekly, Monthly, Yearly }

/** Matches the server's `System.DayOfWeek` string names. */
@Serializable
enum class ApiDayOfWeek { Sunday, Monday, Tuesday, Wednesday, Thursday, Friday, Saturday }

@Serializable
data class CreateRecurrenceRule(
    val frequency: Frequency,
    val startDate: Instant,
    val interval: Int = 1,
    val byHour: List<Int>? = null,
    val byMinute: List<Int>? = null,
    val byDayOfWeek: List<ApiDayOfWeek>? = null,
    val byMonthDay: List<Int>? = null,
    val bySetPos: List<Int>? = null,
    val byMonth: List<Int>? = null,
    val count: Int? = null,
    val until: Instant? = null,
    val exceptions: List<Instant>? = null,
    val timeZone: String? = null,
)

@Serializable
data class CreateOneTimeTodo(
    val title: String,
    val description: String = "",
    val notificationEnabled: Boolean = false,
    val dueDate: Instant,
    val notifyBeforeInMinutes: Int = 15,
    val pomodoroTemplateId: String? = null,
)

@Serializable
data class CreateRecurringTodo(
    val title: String,
    val description: String = "",
    val notificationEnabled: Boolean = false,
    val recurrenceRule: CreateRecurrenceRule,
    val notifyBeforeInMinutes: Int = 15,
    val pomodoroTemplateId: String? = null,
)

@Serializable
data class UpdateTodo(
    val title: String,
    val description: String = "",
    val notificationEnabled: Boolean = false,
    val pomodoroTemplateId: String? = null,
    val recurrenceRule: CreateRecurrenceRule? = null,
    val notifyBeforeInMinutes: Int? = null,
)

@Serializable
data class RescheduleOccurrence(val newDate: Instant, val reason: String? = null)

@Serializable
data class CancelRequest(val reason: String = "")

@Serializable
data class AddRemark(val remark: String)

@Serializable
data class ReplaceRemarks(val remarks: List<String>)

@Serializable
data class RecurrenceRuleResponse(
    val rrule: String,
    val timeZoneId: String,
    val startDate: Instant,
    val frequency: Frequency,
    val interval: Int,
    val count: Int? = null,
    val until: Instant? = null,
    val isOneTime: Boolean = false,
    val exceptions: List<Instant> = emptyList(),
)

@Serializable
data class TodoRemarkResponse(val remark: String, val createdAt: Instant, val updatedAt: Instant)

@Serializable
data class TodoResponse(
    val id: String,
    val title: String,
    val description: String,
    val status: TaskStatus,
    val notificationEnabled: Boolean,
    val notifyBeforeInMinutes: Int,
    val pomodoroTemplateId: String? = null,
    val recurrenceRule: RecurrenceRuleResponse? = null,
    val remarks: List<TodoRemarkResponse> = emptyList(),
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** `id == null` ⇔ `isPreview`: a computed instance beyond the horizon, not yet actionable. */
@Serializable
data class TodoOccurrenceResponse(
    val id: String? = null,
    val todoId: String,
    val todoTitle: String,
    val scheduledAt: Instant,
    val originalScheduledAt: Instant,
    val status: OccurrenceStatus,
    val isRescheduled: Boolean = false,
    val rescheduleReason: String? = null,
    val completedAt: Instant? = null,
    val cancelledAt: Instant? = null,
    val cancellationReason: String? = null,
    val remarks: String? = null,
    val isPreview: Boolean = false,
)

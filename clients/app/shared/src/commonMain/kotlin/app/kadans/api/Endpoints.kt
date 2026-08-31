package app.kadans.api

import app.kadans.api.model.*
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import kotlin.time.Instant
import kotlinx.serialization.Serializable

@Serializable
data class Success(val dummy: String? = null)

class AuthApi internal constructor(private val api: KadansApi) {
    /** Saves the session on success; an MFA challenge saves nothing until [verifyMfa]. */
    suspend fun login(username: String, password: String): LoginResponse =
        api.http.post("auth/login") { setBody(LoginRequest(username, password)) }
            .orThrow<LoginResponse>()
            .also { api.adopt(it) }

    suspend fun verifyMfa(mfaToken: String, code: String): LoginResponse =
        api.http.post("auth/mfa/verify") { setBody(MfaVerifyRequest(mfaToken, code)) }
            .orThrow<LoginResponse>()
            .also { api.adopt(it) }

    suspend fun loginExternal(provider: String, idToken: String): LoginResponse =
        api.http.post("auth/external") { setBody(ExternalLoginRequest(provider, idToken)) }
            .orThrow<LoginResponse>()
            .also { api.adopt(it) }

    suspend fun register(request: RegisterUserRequest): UserResponse =
        api.http.post("auth/register") { setBody(request) }.orThrow()

    suspend fun forgotPassword(email: String) {
        api.http.post("auth/forgot-password") { setBody(ForgotPasswordRequest(email)) }.orThrow<Success>()
    }

    suspend fun resetPassword(request: ResetPasswordRequest) {
        api.http.post("auth/reset-password") { setBody(request) }.orThrow<Success>()
    }

    /** Revokes this session's family server-side and forgets the local session. */
    suspend fun logout() {
        val tokens = api.tokenStore.load()
        if (tokens != null) {
            try {
                api.http.post("auth/revoke") { setBody(RevokeRefreshTokenRequest(tokens.refreshToken)) }
                    .orThrow<Success>()
            } finally {
                api.tokenStore.save(null)
                api.invalidateTokenCache()
            }
        }
    }
}

class AccountApi internal constructor(private val api: KadansApi) {
    suspend fun me(): UserResponse = api.http.get("users/me").orThrow()

    suspend fun update(request: UpdateSelfUserRequest): UserResponse =
        api.http.put("users/me") { setBody(request) }.orThrow()

    suspend fun changePassword(currentPassword: String, newPassword: String) {
        api.http.put("users/me/password") { setBody(ChangePasswordRequest(currentPassword, newPassword)) }
            .orThrow<Success>()
        api.tokenStore.save(null)
        api.invalidateTokenCache()
    }

    suspend fun registerDevice(installationId: String, request: RegisterDeviceRequest): DeviceResponse =
        api.http.put("users/me/devices/$installationId") { setBody(request) }.orThrow()

    suspend fun devices(): List<DeviceResponse> = api.http.get("users/me/devices").orThrow()

    suspend fun removeDevice(installationId: String) {
        api.http.delete("users/me/devices/$installationId").orThrow<Success>()
    }
}

class TodosApi internal constructor(private val api: KadansApi) {
    suspend fun list(page: Int = 1, pageSize: Int = 20, status: TaskStatus? = null): List<TodoResponse> =
        api.http.get("todos") {
            parameter("page", page)
            parameter("pageSize", pageSize)
            if (status != null) parameter("status", status.name)
        }.orThrow()

    suspend fun get(id: String): TodoResponse = api.http.get("todos/$id").orThrow()

    suspend fun createOneTime(request: CreateOneTimeTodo): TodoResponse =
        api.http.post("todos/one-time") { setBody(request) }.orThrow()

    suspend fun createRecurring(request: CreateRecurringTodo): TodoResponse =
        api.http.post("todos/recurring") { setBody(request) }.orThrow()

    suspend fun update(id: String, request: UpdateTodo): TodoResponse =
        api.http.put("todos/$id") { setBody(request) }.orThrow()

    suspend fun cancel(id: String, reason: String = "") {
        api.http.put("todos/$id/cancel") { setBody(CancelRequest(reason)) }.orThrow<Success>()
    }

    suspend fun rescheduleNext(id: String, request: RescheduleOccurrence): TodoOccurrenceResponse =
        api.http.put("todos/$id/reschedule") { setBody(request) }.orThrow()

    suspend fun addRemark(id: String, remark: String) {
        api.http.post("todos/$id/remarks") { setBody(AddRemark(remark)) }.orThrow<Success>()
    }

    suspend fun occurrences(todoId: String, page: Int = 1, pageSize: Int = 20): List<TodoOccurrenceResponse> =
        api.http.get("todos/$todoId/occurrences") {
            parameter("page", page)
            parameter("pageSize", pageSize)
        }.orThrow()

    suspend fun history(todoId: String, page: Int = 1, pageSize: Int = 20): List<TodoOccurrenceResponse> =
        api.http.get("todos/$todoId/history") {
            parameter("page", page)
            parameter("pageSize", pageSize)
        }.orThrow()

    /** Materialized rows plus computed previews (`isPreview`) beyond the horizon. */
    suspend fun occurrencesBetween(from: Instant, to: Instant): List<TodoOccurrenceResponse> =
        api.http.get("occurrences") {
            parameter("from", from.toString())
            parameter("to", to.toString())
        }.orThrow()

    suspend fun completeOccurrence(occurrenceId: String) {
        api.http.put("occurrences/$occurrenceId/complete").orThrow<Success>()
    }

    suspend fun cancelOccurrence(occurrenceId: String, reason: String = "") {
        api.http.put("occurrences/$occurrenceId/cancel") { setBody(CancelRequest(reason)) }.orThrow<Success>()
    }

    suspend fun rescheduleOccurrence(occurrenceId: String, request: RescheduleOccurrence): TodoOccurrenceResponse =
        api.http.put("occurrences/$occurrenceId/reschedule") { setBody(request) }.orThrow()
}

class PomodoroApi internal constructor(private val api: KadansApi) {
    suspend fun templates(): List<PomodoroTemplateResponse> = api.http.get("pomodoro/templates").orThrow()

    suspend fun createTemplate(request: CreatePomodoroTemplate): PomodoroTemplateResponse =
        api.http.post("pomodoro/templates") { setBody(request) }.orThrow()

    suspend fun attachTemplate(todoId: String, templateId: String?) {
        api.http.put("todos/$todoId/pomodoro-template") { setBody(UpdateTodoPomodoro(templateId)) }
            .orThrow<Success>()
    }

    suspend fun start(todoId: String, autoAdvance: Boolean = false): PomodoroRunResponse =
        api.http.post("todos/$todoId/pomodoro/start") { parameter("autoAdvance", autoAdvance) }.orThrow()

    suspend fun activeRun(todoId: String): PomodoroRunResponse =
        api.http.get("todos/$todoId/pomodoro/active-run").orThrow()

    suspend fun runs(todoId: String, page: Int = 1, pageSize: Int = 20): List<PomodoroRunResponse> =
        api.http.get("todos/$todoId/pomodoro/runs") {
            parameter("page", page)
            parameter("pageSize", pageSize)
        }.orThrow()

    suspend fun pause(runId: String): PomodoroRunResponse =
        api.http.put("pomodoro/runs/$runId/pause").orThrow()

    suspend fun resume(runId: String): PomodoroRunResponse =
        api.http.put("pomodoro/runs/$runId/resume").orThrow()

    suspend fun advance(runId: String, expectedPhaseIndex: Int? = null): PomodoroRunResponse =
        api.http.put("pomodoro/runs/$runId/advance") { setBody(AdvancePomodoroRun(expectedPhaseIndex)) }
            .orThrow()

    suspend fun cancel(runId: String): PomodoroRunResponse =
        api.http.put("pomodoro/runs/$runId/cancel").orThrow()

    suspend fun stats(from: Instant? = null, to: Instant? = null): PomodoroStatsResponse =
        api.http.get("pomodoro/stats") {
            if (from != null) parameter("from", from.toString())
            if (to != null) parameter("to", to.toString())
        }.orThrow()
}

class NotificationsApi internal constructor(private val api: KadansApi) {
    suspend fun list(unreadOnly: Boolean = false, page: Int = 1, pageSize: Int = 20): List<NotificationResponse> =
        api.http.get("notifications") {
            parameter("unreadOnly", unreadOnly)
            parameter("page", page)
            parameter("pageSize", pageSize)
        }.orThrow()

    suspend fun unreadCount(): Int = api.http.get("notifications/unread-count").orThrow<UnreadCountResponse>().unread

    suspend fun markRead(id: String) {
        api.http.put("notifications/$id/read").orThrow<Success>()
    }

    suspend fun markAllRead() {
        api.http.put("notifications/read-all").orThrow<Success>()
    }
}

/** Store a completed login (never an MFA challenge) and refresh Ktor's cached bearer. */
internal suspend fun KadansApi.adopt(login: LoginResponse) {
    if (!login.mfaRequired && login.accessToken != null && login.refreshToken != null) {
        tokenStore.save(AuthTokens(login.accessToken, login.refreshToken))
        invalidateTokenCache()
    }
}

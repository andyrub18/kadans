package app.kadans.ui

import io.ktor.http.Url
import kotlin.uuid.Uuid
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/** The host of the emailed links, the one the app's App Links filter claims (AndroidManifest, the server's `AppLinks`). */
private const val EMAILED_LINKS_HOST = "api.kadansplanning.com"

/**
 * Maps a link (a reminder's `kadans://`, the emailed password reset) to the route it opens.
 * Unknown or malformed links resolve to null — the app just opens normally.
 */
fun parseDeepLink(link: String?): Any? {
    if (link.isNullOrBlank()) return null
    val url = runCatching { Url(link) }.getOrNull() ?: return null
    if (url.protocol.name == "https") return emailedLink(url)
    if (url.protocol.name != "kadans") return null
    val path = url.encodedPath.trim('/')
    return when (url.host) {
        // A reminder's todo, or its focus session. Only an id: anything else in the path is not a link this app made.
        "todos" -> {
            val parts = path.split('/')
            val todoId = Uuid.parseOrNull(parts[0])?.toString() ?: return null
            when (parts.drop(1)) {
                emptyList<String>() -> TodoDetailRoute(todoId)
                listOf("focus") -> PomodoroRoute(todoId, startsOne = false)
                else -> null
            }
        }
        // A category limit's alert: the budget.
        "budget" -> if (path.isEmpty()) BudgetRoute else null
        else -> null
    }
}

/** The emailed password reset, from its own host. A reset token never comes through `kadans://`, which any app can claim. */
private fun emailedLink(url: Url): Any? {
    if (url.host != EMAILED_LINKS_HOST || url.encodedPath != "/auth/reset-password") return null
    val email = url.parameters["email"] ?: return null
    val token = url.parameters["token"] ?: return null
    return ResetPasswordRoute(email, token)
}

/** What a reminder opens when tapped: its todo. */
fun todoLink(todoId: String): String = "kadans://todos/$todoId"

/** What a focus session started from a reminder opens: the session (it shows the one running). */
fun focusLink(todoId: String): String = "kadans://todos/$todoId/focus"

/** What a category limit's alert opens: the budget. */
const val BUDGET_LINK = "kadans://budget"

/** Whether [route] may open now: a todo, its session or the budget needs a session, a password reset does not. */
fun opensWith(route: Any, signedIn: Boolean): Boolean =
    (route !is TodoDetailRoute && route !is PomodoroRoute && route !is BudgetRoute) || signedIn

/**
 * Links that reach the app while it runs (Android: a reminder tapped with the app open). Kept until the navigation
 * takes it: Android may have stopped the app in the background, and then the link arrives while it starts again,
 * before any screen listens.
 */
object IncomingLinks {
    private val pending = Channel<String>(Channel.CONFLATED)
    val links: Flow<String> = pending.receiveAsFlow()

    fun open(link: String) {
        pending.trySend(link)
    }
}

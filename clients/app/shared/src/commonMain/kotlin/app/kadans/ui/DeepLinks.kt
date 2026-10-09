package app.kadans.ui

import io.ktor.http.Url
import kotlin.uuid.Uuid
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Maps a `kadans://` link (from an email landing page, a reminder, or an OS intent) to the route it opens.
 * Unknown or malformed links resolve to null — the app just opens normally.
 */
fun parseDeepLink(link: String?): Any? {
    if (link.isNullOrBlank()) return null
    val url = runCatching { Url(link) }.getOrNull() ?: return null
    if (url.protocol.name != "kadans") return null
    val path = url.encodedPath.trim('/')
    return when (url.host) {
        "auth" -> when (path) {
            "reset-password" -> {
                val email = url.parameters["email"] ?: return null
                val token = url.parameters["token"] ?: return null
                ResetPasswordRoute(email, token)
            }
            else -> null
        }
        // A reminder's todo. Only an id: anything else in the path is not a link this app made.
        "todos" -> Uuid.parseOrNull(path)?.let { TodoDetailRoute(it.toString()) }
        else -> null
    }
}

/** What a reminder opens when tapped: its todo. */
fun todoLink(todoId: String): String = "kadans://todos/$todoId"

/** Whether [route] may open now: a todo needs a session, a password reset does not. */
fun opensWith(route: Any, signedIn: Boolean): Boolean = route !is TodoDetailRoute || signedIn

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

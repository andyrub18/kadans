package app.kadans.ui

import io.ktor.http.Url

/**
 * Maps a `kadans://` link (from an email landing page, or an OS intent) to the route it opens.
 * Unknown or malformed links resolve to null — the app just opens normally.
 */
fun parseDeepLink(link: String?): Any? {
    if (link.isNullOrBlank()) return null
    val url = runCatching { Url(link) }.getOrNull() ?: return null
    if (url.protocol.name != "kadans" || url.host != "auth") return null
    return when (url.encodedPath.trimStart('/')) {
        "reset-password" -> {
            val email = url.parameters["email"] ?: return null
            val token = url.parameters["token"] ?: return null
            ResetPasswordRoute(email, token)
        }
        else -> null
    }
}

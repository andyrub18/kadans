package app.kadans.auth

import app.kadans.api.model.GoogleProviderResponse
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

actual fun platformGoogleSignIn(returnToAppText: () -> String): GoogleSignIn = JvmGoogleSignIn(returnToAppText)

/** The pure half of Google's "OAuth 2.0 for desktop apps" loopback flow (RFC 8252 + PKCE, RFC 7636). */
internal object GoogleLoopback {
    const val AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"

    sealed interface Callback {
        data class Code(val code: String) : Callback
        data class Denied(val error: String) : Callback

        /** Not the redirect we wait for (favicon, a stale tab with another state…): keep listening. */
        data object Ignore : Callback
    }

    private val urlSafe = Base64.getUrlEncoder().withoutPadding()

    fun newVerifier(random: SecureRandom = SecureRandom()): String =
        urlSafe.encodeToString(ByteArray(48).also(random::nextBytes))

    fun challengeOf(verifier: String): String =
        urlSafe.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    fun authorizationUrl(clientId: String, redirectUri: String, challenge: String, state: String): String {
        val query = listOf(
            "client_id" to clientId,
            "redirect_uri" to redirectUri,
            "response_type" to "code",
            "scope" to "openid email profile",
            "code_challenge" to challenge,
            "code_challenge_method" to "S256",
            "state" to state,
            "prompt" to "select_account",
        ).joinToString("&") { (key, value) -> key + "=" + URLEncoder.encode(value, Charsets.UTF_8) }
        return "$AUTH_ENDPOINT?$query"
    }

    /** [requestLine] is the first line of the browser's request, e.g. `GET /?code=…&state=… HTTP/1.1`. */
    fun parseCallback(requestLine: String?, expectedState: String): Callback {
        val target = requestLine?.split(' ')?.getOrNull(1) ?: return Callback.Ignore
        val query = target.substringAfter('?', "").takeIf { it.isNotEmpty() } ?: return Callback.Ignore
        val params = query.split('&').mapNotNull { pair ->
            val key = pair.substringBefore('=')
            if (key.isEmpty()) null else key to URLDecoder.decode(pair.substringAfter('=', ""), Charsets.UTF_8)
        }.toMap()

        if (params["state"] != expectedState) return Callback.Ignore
        params["error"]?.let { return Callback.Denied(it) }
        return params["code"]?.takeIf { it.isNotEmpty() }?.let(Callback::Code) ?: Callback.Ignore
    }
}

/**
 * Opens the system browser on Google's consent page and waits for the redirect on a throwaway
 * port of 127.0.0.1. Returns the authorization code — never an ID token: the exchange needs the
 * Desktop client's secret, which only the server has.
 */
internal class JvmGoogleSignIn(
    private val returnToAppText: () -> String,
    private val openBrowser: (String) -> Unit = ::openInBrowser,
    private val timeoutMillis: Int = 180_000,
) : GoogleSignIn {
    override val isSupported = true

    override fun canSignIn(config: GoogleProviderResponse) = !config.desktopClientId.isNullOrBlank()

    override suspend fun signIn(config: GoogleProviderResponse): GoogleCredential? {
        val clientId = config.desktopClientId?.takeIf { it.isNotBlank() }
            ?: throw GoogleSignInException("No desktop client id published by the server")

        val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val redirectUri = "http://127.0.0.1:${server.localPort}"
        val verifier = GoogleLoopback.newVerifier()
        val state = GoogleLoopback.newVerifier().take(24)
        val url = GoogleLoopback.authorizationUrl(clientId, redirectUri, GoogleLoopback.challengeOf(verifier), state)

        return suspendCancellableCoroutine { continuation ->
            // accept() ignores coroutine cancellation; closing the socket is what unblocks it.
            continuation.invokeOnCancellation { runCatching { server.close() } }
            thread(name = "kadans-google-loopback", isDaemon = true) {
                try {
                    openBrowser(url)
                    val deadline = System.currentTimeMillis() + timeoutMillis
                    var outcome: GoogleLoopback.Callback = GoogleLoopback.Callback.Ignore
                    while (outcome is GoogleLoopback.Callback.Ignore) {
                        val left = (deadline - System.currentTimeMillis()).toInt()
                        if (left <= 0) throw SocketTimeoutException()
                        server.soTimeout = left
                        server.accept().use { socket ->
                            socket.soTimeout = 5_000
                            val requestLine = runCatching { socket.getInputStream().bufferedReader().readLine() }.getOrNull()
                            outcome = GoogleLoopback.parseCallback(requestLine, state)
                            val found = outcome !is GoogleLoopback.Callback.Ignore
                            val body = if (found) page(returnToAppText()) else ""
                            val bytes = body.toByteArray(Charsets.UTF_8)
                            socket.getOutputStream().apply {
                                write(
                                    ("HTTP/1.1 ${if (found) "200 OK" else "404 Not Found"}\r\n" +
                                        "Content-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\n" +
                                        "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII)
                                )
                                write(bytes)
                                flush()
                            }
                        }
                    }
                    when (val result = outcome) {
                        is GoogleLoopback.Callback.Code ->
                            continuation.resume(GoogleCredential.AuthorizationCode(result.code, verifier, redirectUri))
                        // "access_denied" is the user pressing Cancel on Google's page.
                        is GoogleLoopback.Callback.Denied ->
                            if (result.error == "access_denied") continuation.resume(null)
                            else continuation.resumeWithException(GoogleSignInException(result.error))
                        GoogleLoopback.Callback.Ignore -> continuation.resume(null)
                    }
                } catch (_: SocketTimeoutException) {
                    if (continuation.isActive) continuation.resume(null) // nobody finished the browser flow
                } catch (e: SocketException) {
                    if (continuation.isActive) continuation.resumeWithException(GoogleSignInException("Loopback listener failed", e))
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(GoogleSignInException(e.message ?: "Google sign-in failed", e))
                } finally {
                    runCatching { server.close() }
                }
            }
        }
    }

    private fun page(text: String): String {
        val safe = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        return "<!doctype html><html><head><meta charset=\"utf-8\"><title>Kadans</title></head>" +
            "<body style=\"font-family:system-ui,sans-serif;text-align:center;padding-top:20vh\">" +
            "<h2>Kadans</h2><p>$safe</p></body></html>"
    }
}

private fun openInBrowser(url: String) {
    val desktop = if (java.awt.Desktop.isDesktopSupported()) java.awt.Desktop.getDesktop() else null
    if (desktop != null && desktop.isSupported(java.awt.Desktop.Action.BROWSE)) {
        desktop.browse(URI(url))
        return
    }
    // Minimal Linux window managers have no AWT desktop integration, but do ship xdg-open.
    val os = System.getProperty("os.name").lowercase()
    val command = when {
        "mac" in os -> listOf("open", url)
        "win" in os -> listOf("rundll32", "url.dll,FileProtocolHandler", url)
        else -> listOf("xdg-open", url)
    }
    ProcessBuilder(command).start()
}

package app.kadans.auth

import app.kadans.api.model.GoogleProviderResponse

/** What a platform's Google flow hands back for the API to turn into a Kadans session. */
sealed interface GoogleCredential {
    /** Android (Credential Manager): an ID token whose audience is the server's Web client id. */
    data class IdToken(val idToken: String) : GoogleCredential

    /** Desktop (loopback + PKCE): the server trades this for the ID token — it holds the secret. */
    data class AuthorizationCode(val code: String, val codeVerifier: String, val redirectUri: String) : GoogleCredential
}

/** The platform flow failed (not: the user backed out — that is a null credential). */
class GoogleSignInException(message: String, cause: Throwable? = null) : Exception(message, cause)

interface GoogleSignIn {
    /** False where no flow exists (iOS for now): nothing is asked of the server, no button shown. */
    val isSupported: Boolean

    /** True when the server published the client id this platform's flow needs. */
    fun canSignIn(config: GoogleProviderResponse): Boolean

    /** Suspends while the user is in Google's UI. Null = the user cancelled. */
    suspend fun signIn(config: GoogleProviderResponse): GoogleCredential?
}

object NoGoogleSignIn : GoogleSignIn {
    override val isSupported = false
    override fun canSignIn(config: GoogleProviderResponse) = false
    override suspend fun signIn(config: GoogleProviderResponse): GoogleCredential? = null
}

/** [returnToAppText] is shown on the browser page that ends the desktop flow. */
expect fun platformGoogleSignIn(returnToAppText: () -> String): GoogleSignIn

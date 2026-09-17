package app.kadans.auth

import android.app.Activity
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import app.kadans.api.model.GoogleProviderResponse
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import java.lang.ref.WeakReference

/**
 * Credential Manager shows its sheet on top of an Activity, and the shared code has none of its
 * own: the launcher registers itself here for as long as it is alive.
 */
object AndroidActivityHolder {
    private var current: WeakReference<Activity>? = null

    fun attach(activity: Activity) {
        current = WeakReference(activity)
    }

    fun detach(activity: Activity) {
        if (current?.get() === activity) current = null
    }

    internal fun get(): Activity? = current?.get()
}

actual fun platformGoogleSignIn(returnToAppText: () -> String): GoogleSignIn = AndroidGoogleSignIn

/**
 * "Sign in with Google" through Credential Manager. The ID token's audience is the server's
 * **Web** client id (`serverClientId`); the Android OAuth client (package + SHA-1) only has to
 * exist in the same Google Cloud project — it is how Google recognises this app.
 */
private object AndroidGoogleSignIn : GoogleSignIn {
    override val isSupported = true

    override fun canSignIn(config: GoogleProviderResponse) = !config.webClientId.isNullOrBlank()

    override suspend fun signIn(config: GoogleProviderResponse): GoogleCredential? {
        val webClientId = config.webClientId?.takeIf { it.isNotBlank() }
            ?: throw GoogleSignInException("No web client id published by the server")
        val activity = AndroidActivityHolder.get() ?: throw GoogleSignInException("No foreground activity")

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(GetSignInWithGoogleOption.Builder(webClientId).build())
            .build()

        val credential = try {
            CredentialManager.create(activity).getCredential(activity, request).credential
        } catch (_: GetCredentialCancellationException) {
            return null
        } catch (e: GetCredentialException) {
            throw GoogleSignInException(e.message ?: e.type, e)
        }

        if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            return GoogleCredential.IdToken(GoogleIdTokenCredential.createFrom(credential.data).idToken)
        }
        throw GoogleSignInException("Unexpected credential type ${credential.type}")
    }
}

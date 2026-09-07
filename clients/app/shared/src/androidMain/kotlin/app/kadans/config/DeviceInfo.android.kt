package app.kadans.config

import android.os.Build
import app.kadans.api.model.DevicePlatform
import com.google.firebase.messaging.FirebaseMessaging
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

actual fun devicePlatform(): DevicePlatform = DevicePlatform.Android

actual fun deviceName(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

/** Null when Firebase isn't configured (no google-services.json baked into the build). */
actual suspend fun currentPushToken(): String? = try {
    suspendCancellableCoroutine { continuation ->
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { continuation.resume(it) }
            .addOnFailureListener { continuation.resume(null) }
    }
} catch (_: Throwable) {
    null
}

package app.kadans.files

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import app.kadans.auth.AndroidActivityHolder
import app.kadans.config.AndroidAppContext
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

actual fun platformFilePorts(): FilePorts = AndroidFilePorts(AndroidAppContext.context)

/**
 * Android's document picker (the Storage Access Framework): the person picks one file, in Downloads, on Drive or
 * wherever a bank's app saved it, and Kadans is handed that one file and nothing else. No storage permission is asked
 * or held, and nothing is kept of the file's address once it is read or written.
 *
 * Any kind of file can be picked: banks name a statement's type every which way, and a file greyed out for its type
 * could not be chosen at all. What a file is is told from what is in it.
 */
internal class AndroidFilePorts(private val context: Context) : FilePorts {
    override val available = true

    override suspend fun open(title: String, maxBytes: Int): PickedFile? {
        val activity = AndroidActivityHolder.get() as? ComponentActivity ?: return null
        val uri = try {
            activity.ask(ActivityResultContracts.OpenDocument(), arrayOf("*/*")) ?: return null
        } catch (_: NoPicker) {
            return PickedFile("", null)
        }
        return withContext(Dispatchers.IO) {
            val name = FilePorts.shown(nameOf(uri))
            try {
                val stream = context.contentResolver.openInputStream(uri) ?: return@withContext PickedFile(name, null)
                val bytes = stream.use { opened -> FilePorts.readAtMost(maxBytes) { opened.read(it) } }
                PickedFile(name, bytes, tooLarge = bytes == null)
            } catch (_: Exception) {
                // Gone meanwhile, or its provider would not hand it over (a cloud file, offline).
                PickedFile(name, null)
            }
        }
    }

    override suspend fun save(title: String, suggestedName: String, mimeType: String, bytes: ByteArray): SaveResult {
        val activity = AndroidActivityHolder.get() as? ComponentActivity ?: return SaveResult.Failed
        val uri = try {
            activity.ask(ActivityResultContracts.CreateDocument(mimeType), suggestedName) ?: return SaveResult.Cancelled
        } catch (_: NoPicker) {
            return SaveResult.Failed
        }
        return withContext(Dispatchers.IO) {
            try {
                (context.contentResolver.openOutputStream(uri, "w") ?: error("no stream")).use { it.write(bytes) }
                SaveResult.Saved
            } catch (_: Exception) {
                // The picker made the file before anything was written: an empty one is not left behind.
                runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
                SaveResult.Failed
            }
        }
    }

    private fun nameOf(uri: Uri): String? = column(uri, OpenableColumns.DISPLAY_NAME) { it.getString(0) }

    private fun <T> column(uri: Uri, name: String, read: (android.database.Cursor) -> T?): T? = runCatching {
        context.contentResolver.query(uri, arrayOf(name), null, null, null)?.use { rows -> if (rows.moveToFirst()) read(rows) else null }
    }.getOrNull()
}

/** This phone has nothing that shows a document picker (a stripped-down system): there is no place to ask for. */
private class NoPicker : Exception()

/**
 * Shows one of the system's pickers and waits for the person's answer; null when they chose nothing. Registered under
 * a name of its own each time, and taken off when answered: an answer that comes for a picker nobody waits for any
 * more (the activity started again meanwhile) is dropped, never handed to the next question.
 */
private suspend fun <I, O> ComponentActivity.ask(contract: ActivityResultContract<I, O?>, input: I): O? =
    withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine { waiting ->
            var launcher: ActivityResultLauncher<I>? = null
            launcher = activityResultRegistry.register("kadans.files." + UUID.randomUUID(), contract) { answer ->
                launcher?.unregister()
                if (waiting.isActive) waiting.resume(answer)
            }
            waiting.invokeOnCancellation { launcher.unregister() }
            try {
                launcher.launch(input)
            } catch (_: android.content.ActivityNotFoundException) {
                launcher.unregister()
                if (waiting.isActive) waiting.resumeWith(Result.failure(NoPicker()))
            }
        }
    }

package app.kadans.files

import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

actual fun platformFilePorts(): FilePorts = DesktopFilePorts

/**
 * The computer's own "Open" and "Save" dialogs (AWT's `FileDialog`: GTK's on Linux, the system's on Windows and macOS,
 * which ask before a file is replaced). Shown from a background thread, which waits there until the dialog closes:
 * the window keeps drawing behind it.
 */
internal object DesktopFilePorts : FilePorts {
    override val available = true

    override suspend fun open(title: String, maxBytes: Int): PickedFile? = withContext(Dispatchers.IO) {
        val dialog = FileDialog(window(), title, FileDialog.LOAD)
        dialog.isVisible = true
        val file = dialog.files.firstOrNull() ?: return@withContext null
        read(file, maxBytes)
    }

    override suspend fun save(title: String, suggestedName: String, mimeType: String, bytes: ByteArray): SaveResult = withContext(Dispatchers.IO) {
        val dialog = FileDialog(window(), title, FileDialog.SAVE)
        dialog.file = suggestedName
        dialog.isVisible = true
        val chosen = dialog.files.firstOrNull() ?: return@withContext SaveResult.Cancelled
        write(withExtension(chosen, suggestedName), bytes)
    }

    /** The app's window, which the dialog belongs to; none (closed to the tray meanwhile): a dialog of its own. */
    private fun window(): Frame? = Frame.getFrames().firstOrNull { it.isShowing }

    internal fun read(file: File, maxBytes: Int): PickedFile {
        val name = FilePorts.shown(file.name)
        if (file.length() > maxBytes) return PickedFile(name, null, tooLarge = true)
        return try {
            val bytes = file.inputStream().use { stream -> FilePorts.readAtMost(maxBytes) { stream.read(it) } }
            PickedFile(name, bytes, tooLarge = bytes == null)
        } catch (_: Exception) {
            PickedFile(name, null)
        }
    }

    /**
     * A name typed without an extension gets the suggested one, so the file opens with a double click. Only where no
     * such file exists yet: the dialog asked about replacing the name as typed, not that one.
     */
    internal fun withExtension(chosen: File, suggestedName: String): File {
        val extension = suggestedName.substringAfterLast('.', "")
        if (extension.isEmpty() || '.' in chosen.name) return chosen
        return File(chosen.parentFile, chosen.name + "." + extension).takeIf { !it.exists() } ?: chosen
    }

    internal fun write(file: File, bytes: ByteArray): SaveResult = try {
        file.writeBytes(bytes)
        SaveResult.Saved
    } catch (_: Exception) {
        // Half a file is worse than none.
        runCatching { if (file.length() != bytes.size.toLong()) file.delete() }
        SaveResult.Failed
    }
}

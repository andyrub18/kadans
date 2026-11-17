package app.kadans.files

/**
 * A file the person picked with the system's own picker. [bytes] is what is in it; null when it could not be read, or
 * when it is larger than what was asked for ([tooLarge]): such a file is never read whole.
 */
class PickedFile(val name: String, val bytes: ByteArray?, val tooLarge: Boolean = false)

enum class SaveResult {
    Saved,

    /** The person closed the dialog without choosing a place. */
    Cancelled,

    /** No place could be asked for, or the file could not be written there. */
    Failed,
}

/**
 * Files in and out of the app, each through the system's own dialog (ARCHITECTURE → "Statements in, data out"): the
 * person picks one file to read, or the place where one is saved. Kadans asks for no access to the device's storage
 * and sees nothing but that one file: no permission, on Android as on a computer.
 */
interface FilePorts {
    /** Whether this device has such dialogs. Where it has none, what needs them is not offered. */
    val available: Boolean

    /**
     * Asks for one file and reads it, up to [maxBytes]. Null: none chosen. [title] heads the dialog where the system
     * shows one (a computer; Android's picker has its own).
     */
    suspend fun open(title: String, maxBytes: Int): PickedFile?

    /** Asks where to keep [bytes], offering [suggestedName], and writes them there. */
    suspend fun save(title: String, suggestedName: String, mimeType: String, bytes: ByteArray): SaveResult

    companion object {
        /** The longest name shown of a file: it is whatever the file was called, by whoever made it. */
        const val NAME_LENGTH = 120

        /** A file's name as it is shown: one line of plain text, cut. */
        fun shown(name: String?): String =
            name.orEmpty().filter { !it.isISOControl() }.trim().let { if (it.length <= NAME_LENGTH) it else it.take(NAME_LENGTH - 1) + "…" }

        /**
         * What [read] gives (it fills a buffer and says how much, below zero at the end), up to [maxBytes]. Null when
         * there is more: the rest is left unread.
         */
        inline fun readAtMost(maxBytes: Int, read: (ByteArray) -> Int): ByteArray? {
            val buffer = ByteArray(64 * 1024)
            val parts = ArrayList<ByteArray>()
            var size = 0
            while (true) {
                val count = read(buffer)
                if (count < 0) break
                if (size + count > maxBytes) return null
                parts += buffer.copyOf(count)
                size += count
            }
            val all = ByteArray(size)
            var at = 0
            for (part in parts) {
                part.copyInto(all, at)
                at += part.size
            }
            return all
        }
    }
}

/** A device without file dialogs Kadans can use. */
object NoFilePorts : FilePorts {
    override val available = false

    override suspend fun open(title: String, maxBytes: Int): PickedFile? = null

    override suspend fun save(title: String, suggestedName: String, mimeType: String, bytes: ByteArray) = SaveResult.Failed
}

expect fun platformFilePorts(): FilePorts

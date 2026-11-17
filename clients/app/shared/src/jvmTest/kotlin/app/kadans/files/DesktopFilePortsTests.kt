package app.kadans.files

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The computer's side of a file in and a file out, behind its dialogs (ARCHITECTURE → "Statements in, data out"). */
class DesktopFilePortsTests {
    private val folder: File = createTempDirectory("kadans-files").toFile()

    @AfterTest
    fun after() {
        folder.deleteRecursively()
    }

    @Test
    fun a_file_is_read_whole_or_not_at_all() {
        val statement = File(folder, "novembre.csv").apply { writeText("Date,Details,Amount\n03/11/2026,COFFEE,-3.50\n") }
        val read = DesktopFilePorts.read(statement, maxBytes = 1024)
        assertEquals("novembre.csv", read.name)
        assertContentEquals(statement.readBytes(), read.bytes)
        assertFalse(read.tooLarge)

        // Larger than asked for: said, and not a byte of it kept.
        val large = DesktopFilePorts.read(statement, maxBytes = 10)
        assertNull(large.bytes)
        assertTrue(large.tooLarge)

        // Gone since the dialog showed it.
        val gone = DesktopFilePorts.read(File(folder, "gone.csv"), maxBytes = 1024)
        assertNull(gone.bytes)
        assertFalse(gone.tooLarge)
    }

    @Test
    fun a_stream_longer_than_it_said_is_still_cut_off() {
        // A file that grows while it is read, or one whose size the system does not know: the limit holds.
        var served = 0
        val bytes = FilePorts.readAtMost(maxBytes = 100_000) { buffer ->
            if (served >= 300_000) -1 else buffer.size.also { served += it }
        }
        assertNull(bytes)
        assertTrue(served < 300_000) // the rest was never read

        var left = 150_000
        val whole = FilePorts.readAtMost(maxBytes = 150_000) { buffer ->
            if (left == 0) -1 else minOf(left, buffer.size).also { count -> buffer.fill(7, 0, count); left -= count }
        }
        assertEquals(150_000, whole!!.size)
        assertTrue(whole.all { it == 7.toByte() })
    }

    @Test
    fun a_saved_file_gets_its_extension_unless_that_would_replace_another() {
        val typed = File(folder, "my data")
        assertEquals(File(folder, "my data.zip"), DesktopFilePorts.withExtension(typed, "kadans-export-2026-11-16.zip"))
        // Named in full by the person: left as it is.
        assertEquals(File(folder, "copy.zip"), DesktopFilePorts.withExtension(File(folder, "copy.zip"), "kadans-export-2026-11-16.zip"))
        assertEquals(File(folder, "copy.backup"), DesktopFilePorts.withExtension(File(folder, "copy.backup"), "kadans-export-2026-11-16.zip"))
        // The dialog asked about replacing "my data", not "my data.zip": that one is not touched.
        File(folder, "my data.zip").writeText("an older copy")
        assertEquals(typed, DesktopFilePorts.withExtension(typed, "kadans-export-2026-11-16.zip"))
    }

    @Test
    fun what_is_saved_is_what_was_given_and_a_place_that_refuses_leaves_nothing() {
        val bytes = ByteArray(5_000) { (it % 251).toByte() }
        val target = File(folder, "kadans-export.zip")
        assertEquals(SaveResult.Saved, DesktopFilePorts.write(target, bytes))
        assertContentEquals(bytes, target.readBytes())

        // A folder that is not there (a drive unplugged meanwhile).
        val nowhere = File(File(folder, "missing"), "kadans-export.zip")
        assertEquals(SaveResult.Failed, DesktopFilePorts.write(nowhere, bytes))
        assertFalse(nowhere.exists())
    }

    @Test
    fun a_files_name_is_shown_as_one_line_of_plain_text() {
        assertEquals("relevé novembre.csv", FilePorts.shown("  relevé\u0007 novembre.csv\n"))
        assertEquals(FilePorts.NAME_LENGTH, FilePorts.shown("x".repeat(500) + ".csv").length)
        assertEquals("", FilePorts.shown(null))
    }
}

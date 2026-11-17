package app.kadans.auth

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking

/**
 * The session sealed by Windows' own data protection. It needs Windows: the release workflow's Windows job runs it on
 * a real one (elsewhere each test passes without checking anything).
 */
class WindowsSealerTests {
    private val onWindows = "windows" in System.getProperty("os.name").lowercase()
    private val session = "access.jwt\nrefresh".toByteArray()

    @Test
    fun what_windows_sealed_opens_again_for_the_same_person() = runBlocking {
        if (!onWindows) return@runBlocking
        val sealed = assertIs<Sealing.Done>(WindowsSealer().seal(session)).bytes

        assertContentEquals(session, assertIs<Opening.Done>(WindowsSealer().open(sealed)).bytes)
    }

    @Test
    fun what_is_sealed_never_reads_as_the_session_and_differs_each_time() = runBlocking {
        if (!onWindows) return@runBlocking
        val one = assertIs<Sealing.Done>(WindowsSealer().seal(session)).bytes
        val two = assertIs<Sealing.Done>(WindowsSealer().seal(session)).bytes

        assertFalse(one.contentEquals(two))
        assertFalse(String(one, Charsets.ISO_8859_1).contains("refresh"))
    }

    @Test
    fun what_changed_since_or_was_never_sealed_does_not_open() = runBlocking {
        if (!onWindows) return@runBlocking
        val sealer = WindowsSealer()
        val sealed = assertIs<Sealing.Done>(sealer.seal(session)).bytes

        val changed = sealed.copyOf().also { it[it.lastIndex] = (it[it.lastIndex] + 1).toByte() }
        assertEquals(Opening.Lost, sealer.open(changed))
        assertEquals(Opening.Lost, sealer.open(sealed.copyOf(sealed.size / 2)))
        assertEquals(Opening.Lost, sealer.open(ByteArray(0)))
        assertEquals(Opening.Lost, sealer.open("not sealed at all".toByteArray()))
    }

    @Test
    fun what_another_program_sealed_for_the_same_person_is_not_a_session() {
        if (!onWindows) return
        // Sealed by Windows for this person, without what Kadans mixes in: the store's own tool does that.
        val foreign = ProcessBuilder(
            "powershell", "-NoProfile", "-Command",
            "Add-Type -AssemblyName System.Security; " +
                "[Convert]::ToBase64String([Security.Cryptography.ProtectedData]::Protect(" +
                "[Text.Encoding]::UTF8.GetBytes('access.jwt'), \$null, 'CurrentUser'))",
        ).redirectErrorStream(true).start().inputStream.bufferedReader().readText().trim()
        val sealed = java.util.Base64.getDecoder().decode(foreign)

        assertEquals(Opening.Lost, runBlocking { WindowsSealer().open(sealed) })
    }
}

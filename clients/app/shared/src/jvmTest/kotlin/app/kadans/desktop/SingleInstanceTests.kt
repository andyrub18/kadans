package app.kadans.desktop

import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** A second start asks the running Kadans to show itself instead of running twice (and ringing every reminder twice). */
class SingleInstanceTests {
    private val root = Files.createTempDirectory("kadans-instance")
    private val folder = root.resolve("kadans")
    private val instances = mutableListOf<SingleInstance>()

    @AfterTest
    fun cleanUp() {
        instances.forEach { it.close() }
        root.toFile().deleteRecursively()
    }

    private fun instance() = SingleInstance(folder, answerWithin = 600.milliseconds).also { instances += it }

    @Test
    fun a_second_start_shows_the_first_and_leaves() {
        val shown = CountDownLatch(1)
        assertEquals(SingleInstance.Claim.Only, instance().claim(onShow = { shown.countDown() }))

        assertEquals(SingleInstance.Claim.AskedOther, instance().claim(onShow = {}))
        assertTrue(shown.await(5, TimeUnit.SECONDS), "the first Kadans was asked to show its window")
    }

    @Test
    fun a_socket_left_by_a_crash_does_not_stop_the_next_start() {
        Files.createDirectories(folder)
        Files.writeString(folder.resolve("instance.sock"), "left over")
        val shown = CountDownLatch(1)

        assertEquals(SingleInstance.Claim.Only, instance().claim(onShow = { shown.countDown() }))
        assertEquals(SingleInstance.Claim.AskedOther, instance().claim(onShow = {}))
        assertTrue(shown.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun a_holder_that_never_answers_is_not_joined_by_a_second_copy() {
        Files.createDirectories(folder)
        FileChannel.open(folder.resolve("instance.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                assertEquals(SingleInstance.Claim.OtherUnreachable, instance().claim(onShow = {}))
            }
        }
        // Once that holder is gone, Kadans starts.
        assertEquals(SingleInstance.Claim.Only, instance().claim(onShow = {}))
    }

    @Test
    fun the_folder_is_closed_to_other_users() {
        Files.createDirectories(folder, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxrwxrwx")))
        Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("rwxrwxrwx"))

        assertEquals(SingleInstance.Claim.Only, instance().claim(onShow = {}))
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(folder))
    }

    @Test
    fun no_folder_to_use_never_keeps_kadans_from_starting() {
        assertEquals(SingleInstance.Claim.Only, SingleInstance(dir = null).claim(onShow = {}))
        Files.writeString(root.resolve("a-file"), "not a folder")
        assertEquals(SingleInstance.Claim.Only, SingleInstance(root.resolve("a-file")).claim(onShow = {}))
    }
}

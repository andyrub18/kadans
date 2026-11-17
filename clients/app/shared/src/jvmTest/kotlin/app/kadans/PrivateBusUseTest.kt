package app.kadans

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A test's private D-Bus bus is started with [startPrivateBus], which waits until dbus-java's daemon can take a
 * connection. Started directly, the daemon is used a moment too early now and then, and the test fails before it
 * began ("Disconnected"). This reads the tests and fails on one that starts the daemon itself.
 */
class PrivateBusUseTest {
    @Test
    fun every_test_starts_its_private_bus_through_the_helper() {
        val root = File("src/jvmTest/kotlin")
        assertTrue(root.isDirectory, "run from the shared module")
        val own = setOf("PrivateBus.kt", "PrivateBusUseTest.kt")
        val direct = root.walkTopDown()
            .filter { it.extension == "kt" && it.name !in own }
            .filter { STARTS_IT.containsMatchIn(it.readText()) }
            .map { it.path }
            .toList()

        if (direct.isNotEmpty()) fail("These start dbus-java's daemon themselves; use startPrivateBus(address):\n" + direct.joinToString("\n"))
    }

    private companion object {
        val STARTS_IT = Regex("""EmbeddedDBusDaemon\s*\(|\.startInBackground""")
    }
}

package app.kadans

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests change `Dispatchers.Main` through [TestMain] only. Changed directly, a late answer of a mock server fails a
 * test that passed, and the next one, now and then: a failure that says nothing about the app and sends someone
 * looking for a bug that is not there.
 */
class TestMainUseTest {
    @Test
    fun no_test_changes_main_by_itself() {
        // Gradle runs a module's tests from the module's own folder.
        val sources = listOf("src/commonTest/kotlin", "src/jvmTest/kotlin").map(::File)
        assertTrue(sources.all { it.isDirectory }, "the tests' sources are not where this check looks: ${File("").absolutePath}")
        val direct = Regex("""Dispatchers\s*\.\s*(setMain|resetMain)\s*\(""")
        val offenders = sources.flatMap { root -> root.walkTopDown().filter { it.extension == "kt" }.toList() }
            .filter { it.name != "TestMain.kt" && it.name != "TestMainUseTest.kt" && direct.containsMatchIn(it.readText()) }
            .map { it.path }
        assertEquals(emptyList(), offenders, "use TestMain.set and TestMain.reset in @BeforeTest and @AfterTest")
    }
}

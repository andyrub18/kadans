package app.kadans

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Whatever listens to a signal in the app (a background service, a screen's view model) does it through `listen`
 * (sync/Listening.kt) or `DataChanges.reloadOn`, which subscribe before they return. Written
 * `scope.launch { signals.collect { } }`, it subscribes when that coroutine first runs, and what is sent meanwhile is
 * lost without a trace: a test that passes for weeks and fails on a busy machine, a button pressed as the app opens
 * that does nothing. This reads the app's own code and fails on that way of writing it.
 *
 * A view model is held to it too. On the main thread its scope does start a coroutine at once, but a test runs it on a
 * dispatcher that queues, and a flow that merges or buffers subscribes several turns later whatever the dispatcher.
 * A screen's `LaunchedEffect` is not looked at: it lives with what is on the screen, not with an object's making.
 */
class ListenFromTheStartTest {
    private val roots = listOf("src/commonMain/kotlin", "src/jvmMain/kotlin", "src/androidMain/kotlin", "src/iosMain/kotlin")

    @Test
    fun no_background_listener_subscribes_late() {
        val found = roots.map(::File).filter { it.isDirectory }.also { assertTrue(it.isNotEmpty(), "run from the shared module") }
            .flatMap { root -> root.walkTopDown().filter { it.extension == "kt" }.toList() }
            .flatMap { file -> lateListeners(file.readText()).map { "${file.path}:$it" } }

        if (found.isNotEmpty()) {
            fail(
                "These collect a flow from a coroutine that starts later: what is sent in between is lost. " +
                    "Use scope.listen(signals) { } (sync/Listening.kt), one call per source:\n" + found.joinToString("\n"),
            )
        }
    }

    @Test
    fun it_tells_a_late_listener_from_the_others() {
        fun lines(code: String) = lateListeners(code.trimIndent())
        // Late: launched, then collected.
        assertTrue(lines("init {\n    scope.launch {\n        events.collect { handle(it) }\n    }\n}") == listOf(2))
        assertTrue(lines("fun start() {\n    job = scope.launch { merge(a, b).collect { soon() } }\n}") == listOf(2))
        assertTrue(lines("val x = 1\nscope.launch(Dispatchers.IO) { flow.collectLatest { } }") == listOf(2))
        assertTrue(lines("events.onEach { handle(it) }.launchIn(scope)") == listOf(1))
        assertTrue(lines("viewModelScope.launch { changes.collect { load() } }") == listOf(1))
        assertTrue(lines("events.onEach { }.launchIn(viewModelScope)") == listOf(1))
        // Not late: `listen`, `reloadOn`, a launch that collects nothing, a screen's state.
        assertTrue(lines("scope.listen(events) { handle(it) }").isEmpty())
        assertTrue(lines("dataChanges.reloadOn(viewModelScope, DataArea.Todos) { load(quiet = true) }").isEmpty())
        assertTrue(lines("scope.launch { for (request in requests) attempt { sync() } }").isEmpty())
        assertTrue(lines("scope.launch { save() }\nval state by flow.collectAsState()").isEmpty())
    }

    /** The lines (from 1) where a coroutine is launched and collects a flow, or a flow is launched in a scope. */
    private fun lateListeners(code: String): List<Int> {
        val lines = mutableListOf<Int>()
        fun lineOf(index: Int) = code.substring(0, index).count { it == '\n' } + 1
        for (launch in LAUNCH.findAll(code)) {
            val open = launch.range.last
            var depth = 0
            var end = open
            while (end < code.length) {
                when (code[end]) {
                    '{' -> depth++
                    '}' -> if (--depth == 0) break
                }
                end++
            }
            if (COLLECT.containsMatchIn(code.substring(open, minOf(end + 1, code.length)))) lines += lineOf(launch.range.first)
        }
        for (launchIn in LAUNCH_IN.findAll(code)) lines += lineOf(launchIn.range.first)
        return lines.sorted()
    }

    private companion object {
        /** `something.launch {` or `something.launch(…) {`. */
        val LAUNCH = Regex("""\b\w+\.launch\s*(?:\([^(){}]*\))?\s*\{""")
        val COLLECT = Regex("""\.collect(?:Latest|Indexed)?\s*[({]""")
        val LAUNCH_IN = Regex("""\.launchIn\(""")
    }
}

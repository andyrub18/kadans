package app.kadans.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Back, for a screen with steps of its own (a statement's import: the file, its columns, its lines). The system's Back
 * (Android's gesture, a computer's Escape) first asks the screen in front to go one step back, and leaves it only when
 * the screen has none left. One screen at a time answers: the one in front.
 */
class BackSteps {
    private var step: (() -> Boolean)? = null

    /** True when the screen in front took the Back for itself. */
    fun handled(): Boolean = step?.invoke() == true

    internal fun set(handler: () -> Boolean) {
        step = handler
    }

    internal fun clear(handler: () -> Boolean) {
        if (step === handler) step = null
    }
}

val LocalBackSteps = staticCompositionLocalOf { BackSteps() }

/** While this is on screen, Back asks [step] first: true keeps the person here (one step back), false lets them leave. */
@Composable
fun OnBackStep(step: () -> Boolean) {
    val steps = LocalBackSteps.current
    val current by rememberUpdatedState(step)
    DisposableEffect(steps) {
        val handler = { current() }
        steps.set(handler)
        onDispose { steps.clear(handler) }
    }
}

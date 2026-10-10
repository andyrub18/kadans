package app.kadans.ui.brand

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

/**
 * The Kadans mark: a clock dial whose four hands form a K (branding/README.md). The same geometry as
 * branding/generate.py, which draws the files (icons, the Android splash): change both together.
 */
object KadansMark {
    /** The app's purple (Material 3 baseline primary): the icon's background and the splash. */
    val Purple = Color(0xFF6750A4)
    val PURPLE_ARGB: Int = 0xFF6750A4.toInt()

    /** Where the hands rest (clockwise from 12): the stem up, the upper arm, the stem down, the lower arm. */
    val Angles = listOf(0f, 30f, 180f, 150f)
    internal val Lengths = listOf(29f, 25f, 29f, 25f)

    /** The splash's "Wind": every hand starts at 12 and turns this many times before settling, in one second. */
    internal val WindTurns = listOf(1, 2, 1, 1)
    internal const val WIND_MS = 1000
    internal val WindEasing = CubicBezierEasing(0.2f, 0.78f, 0.25f, 1f)

    /** How long the splash keeps the settled K before it fades: long enough to read the name, not just glimpse it. */
    const val HOLD_MS = 700

    /** The splash's fade into the app. */
    const val FADE_MS = 300
}

/**
 * Draws the mark in this scope's square, [handAngles] where the hands point. [small]: the version for 24 dp and
 * under (no hour marks, bolder strokes), as the notification and tray icons are.
 */
fun DrawScope.drawKadansMark(color: Color, handAngles: List<Float> = KadansMark.Angles, small: Boolean = false) {
    val unit = size.minDimension / 100f
    val centre = Offset(size.width / 2, size.height / 2)
    fun at(angle: Float, r: Float): Offset {
        val a = angle * (kotlin.math.PI.toFloat() / 180f)
        return Offset(centre.x + r * unit * sin(a), centre.y - r * unit * cos(a))
    }
    if (small) {
        drawCircle(color, radius = 42f * unit, center = centre, style = Stroke(width = 7f * unit))
        handAngles.forEachIndexed { i, angle ->
            drawLine(color, centre, at(angle, listOf(27f, 23f, 27f, 23f)[i]), strokeWidth = 9.5f * unit, cap = StrokeCap.Round)
        }
        return
    }
    drawCircle(color, radius = 44f * unit, center = centre, style = Stroke(width = 3.5f * unit))
    for (i in 0 until 12) {
        val major = i % 3 == 0
        drawLine(
            color, at(i * 30f, if (major) 36f else 38f), at(i * 30f, 41.5f),
            strokeWidth = (if (major) 3f else 2f) * unit, cap = StrokeCap.Round,
        )
    }
    handAngles.forEachIndexed { i, angle ->
        rotate(angle, pivot = centre) {
            drawLine(color, centre, Offset(centre.x, centre.y - KadansMark.Lengths[i] * unit), strokeWidth = 6.5f * unit, cap = StrokeCap.Round)
        }
    }
}

/** The app icon as a picture: the white dial on a purple disc (the desktop's window and tray). */
class KadansIconPainter(private val small: Boolean = false) : Painter() {
    override val intrinsicSize: Size = Size(256f, 256f)

    override fun DrawScope.onDraw() {
        drawCircle(KadansMark.Purple, radius = size.minDimension / 2)
        inset(size.minDimension * 0.11f) { drawKadansMark(Color.White, small = small) }
    }
}

/**
 * [content] with the splash over it as it opens, once (a recreated Android activity does not play it again), fading
 * into it. [show] false: the content alone (Android 12+, whose system plays the splash). See [KadansSplash].
 */
@Composable
fun SplashOver(show: Boolean = true, wind: Boolean = true, markSize: Dp = 160.dp, content: @Composable () -> Unit) {
    var splashing by rememberSaveable { mutableStateOf(show) }
    Box(Modifier.fillMaxSize()) {
        content()
        AnimatedVisibility(visible = splashing, enter = EnterTransition.None, exit = fadeOut(tween(KadansMark.FADE_MS))) {
            KadansSplash(onFinished = { splashing = false }, wind = wind, markSize = markSize)
        }
    }
}

/**
 * The splash where the system has none: purple, the hands winding into the K in one second as on Android 12+, then the
 * K held for [KadansMark.HOLD_MS]. [wind] false: the K at rest from the start, held as long (Android before 12, whose
 * window showed that still mark while the app started). [onFinished] once it has been seen. It takes every click and
 * tap meanwhile, so none lands on the app beneath it.
 */
@Composable
fun KadansSplash(onFinished: () -> Unit, modifier: Modifier = Modifier, wind: Boolean = true, markSize: Dp = 160.dp) {
    val hands = remember { KadansMark.Angles.map { Animatable(if (wind) 0f else it) } }
    LaunchedEffect(Unit) {
        if (wind) {
            coroutineScope {
                hands.mapIndexed { i, hand ->
                    async {
                        hand.animateTo(
                            KadansMark.Angles[i] + 360f * KadansMark.WindTurns[i],
                            tween(KadansMark.WIND_MS, easing = KadansMark.WindEasing),
                        )
                    }
                }.awaitAll()
            }
        }
        delay(KadansMark.HOLD_MS.toLong())
        onFinished()
    }
    Box(
        modifier.fillMaxSize().background(KadansMark.Purple).pointerInput(Unit) {
            awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
        },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(markSize)) { drawKadansMark(Color.White, hands.map { it.value % 360f }) }
    }
}

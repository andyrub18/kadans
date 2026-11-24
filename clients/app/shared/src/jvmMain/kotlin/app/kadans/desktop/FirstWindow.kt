package app.kadans.desktop

import java.awt.GraphicsEnvironment

/**
 * The size the desktop window opens at (ARCHITECTURE → "The look"): wide enough for the computer's layout, the
 * destinations in a column on the left and a screen in two columns, and never larger than the screen has room for. A
 * laptop's small screen gets what fits, down to the phone's layout, which the window can always be narrowed to.
 */
object FirstWindow {
    const val WIDTH = 1180
    const val HEIGHT = 800

    /** What is left around the window on a screen it nearly fills. */
    private const val MARGIN = 48
    private const val SMALLEST_WIDTH = 420
    private const val SMALLEST_HEIGHT = 560

    /** Width and height in dp for a screen with [usableWidth] by [usableHeight] free of its panels and taskbar. */
    fun within(usableWidth: Int, usableHeight: Int): Pair<Int, Int> =
        (usableWidth - MARGIN).coerceIn(SMALLEST_WIDTH, WIDTH) to (usableHeight - MARGIN).coerceIn(SMALLEST_HEIGHT, HEIGHT)

    /** The size for this computer's main screen; the full one where the screen cannot be asked (no display). */
    fun size(): Pair<Int, Int> =
        runCatching {
            val usable = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
            within(usable.width, usable.height)
        }.getOrDefault(WIDTH to HEIGHT)
}

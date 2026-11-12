@file:Suppress("FunctionName")

package app.kadans.tray

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import app.kadans.ui.LocalDarkTheme
import java.awt.Point
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrayMenuTests {
    private val w = TrayMenuLayout.WIDTH
    private val h = TrayMenuLayout.HEIGHT

    /** A 1920 × 1080 screen with Windows' taskbar along the bottom (48 px). */
    private val screen = Rectangle(0, 0, 1920, 1032)

    @Test
    fun it_opens_above_the_click_and_to_its_left_as_windows_menus_do() {
        assertEquals(Point(1860 - w, 1020 - h), TrayMenuLayout.place(Point(1860, 1020), screen))
    }

    @Test
    fun a_taskbar_at_the_top_or_the_left_opens_it_below_or_to_the_right() {
        val belowATopTaskbar = Rectangle(0, 48, 1920, 1032)
        assertEquals(Point(1860 - w, 60), TrayMenuLayout.place(Point(1860, 60), belowATopTaskbar))
        val besideALeftTaskbar = Rectangle(48, 0, 1872, 1080)
        assertEquals(Point(60, 1000 - h), TrayMenuLayout.place(Point(60, 1000), besideALeftTaskbar))
    }

    @Test
    fun it_stays_whole_on_the_screen() {
        // A click on the taskbar itself, below the usable area: the menu sits on the area's bottom edge.
        val corner = TrayMenuLayout.place(Point(1900, 1060), screen)
        assertTrue(corner.y + h <= screen.y + screen.height, "bottom ${corner.y + h}")
        assertTrue(corner.x >= screen.x && corner.x + w <= screen.x + screen.width, "x ${corner.x}")
        // A second screen to the left of the main one (negative coordinates).
        val left = Rectangle(-1280, 0, 1280, 984)
        val there = TrayMenuLayout.place(Point(-20, 970), left)
        assertEquals(Point(-20 - w, 970 - h), there)
    }

    private class Taken {
        val actions = mutableListOf<String>()
    }

    private fun scene(dark: Boolean, taken: Taken): ImageComposeScene =
        ImageComposeScene(width = w, height = h, density = Density(1f)) {
            CompositionLocalProvider(LocalDarkTheme provides dark) {
                MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                    TrayMenu(
                        openLabel = "Open Kadans",
                        quitLabel = "Quit",
                        onOpen = { taken.actions += "open" },
                        onQuit = { taken.actions += "quit" },
                        onDismiss = { taken.actions += "dismiss" },
                    )
                }
            }
        }

    @Test
    fun light_or_dark_as_the_app_is() {
        fun backgroundOf(dark: Boolean): Int = scene(dark, Taken()).use { scene ->
            val image = scene.render()
            val pixels = org.jetbrains.skia.Bitmap.makeFromImage(image)
            pixels.getColor(w / 2, h - 3) // inside the padding, under the last row: the menu's own colour
        }
        val light = backgroundOf(dark = false)
        val dark = backgroundOf(dark = true)
        assertEquals(0xFFFFFFFF.toInt(), light, "light: Material's lowest surface, white")
        assertEquals(0xFF2B2930.toInt(), dark, "dark: Material's high surface container")
    }

    @Test
    fun a_click_on_a_row_takes_it() {
        val taken = Taken()
        scene(dark = false, taken).use { scene ->
            scene.render()
            val quitRow = Offset(w / 2f, h - TrayMenuLayout.PADDING - TrayMenuLayout.ROW / 2f)
            scene.sendPointerEvent(PointerEventType.Move, quitRow)
            scene.sendPointerEvent(PointerEventType.Press, quitRow)
            scene.sendPointerEvent(PointerEventType.Release, quitRow)
            scene.render()
        }
        assertEquals(listOf("quit"), taken.actions)
    }

    @Test
    fun the_arrow_keys_pick_a_row_enter_takes_it_and_escape_closes() {
        // Pressed one after the other in a fresh menu (nothing picked); what was taken or closed.
        fun keys(pressed: List<Key>): List<String> {
            var picked = -1
            val done = mutableListOf<String>()
            for (key in pressed) {
                val outcome = menuKey(key, picked, rows = 2) ?: continue
                picked = outcome.picked
                outcome.take?.let { done += listOf("open", "quit")[it] }
                if (outcome.close) done += "dismiss"
            }
            return done
        }
        assertEquals(listOf("open"), keys(listOf(Key.DirectionDown, Key.Enter)))
        assertEquals(listOf("quit"), keys(listOf(Key.DirectionDown, Key.DirectionDown, Key.Enter)))
        assertEquals(listOf("open"), keys(listOf(Key.DirectionDown, Key.DirectionDown, Key.DirectionDown, Key.Spacebar)), "round again")
        assertEquals(listOf("quit"), keys(listOf(Key.DirectionUp, Key.Enter)), "up from nothing picked: the last row")
        assertEquals(emptyList(), keys(listOf(Key.Enter)), "nothing picked yet: Enter does nothing")
        assertEquals(listOf("dismiss"), keys(listOf(Key.Escape)))
        assertEquals(null, menuKey(Key.A, picked = 0, rows = 2), "other keys are not the menu's")
    }
}

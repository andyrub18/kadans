package app.kadans.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

/** The desktop window opens at the computer's layout where the screen has room, and inside the screen where it has not. */
class FirstWindowTests {
    @Test
    fun aLargeScreenGetsTheFullSize() {
        assertEquals(FirstWindow.WIDTH to FirstWindow.HEIGHT, FirstWindow.within(1920, 1040))
        assertEquals(FirstWindow.WIDTH to FirstWindow.HEIGHT, FirstWindow.within(3840, 2100))
    }

    @Test
    fun aSmallLaptopGetsWhatFits() {
        // 1366 x 768 less a taskbar: the width fits, the height is the screen's.
        assertEquals(FirstWindow.WIDTH to 680, FirstWindow.within(1366, 728))
        assertEquals(976 to 560, FirstWindow.within(1024, 608))
    }

    @Test
    fun itNeverOpensSmallerThanAPhone() {
        assertEquals(420 to 560, FirstWindow.within(300, 200))
    }
}

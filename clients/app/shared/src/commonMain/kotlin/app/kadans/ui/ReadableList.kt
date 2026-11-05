package app.kadans.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** What a list leaves below its last item so a floating + never covers it: the button, its margin, a gap. */
val FabClearance: Dp = 88.dp

/**
 * A list's padding on any window: 16 dp on a phone; on a wide one, as much on each side as keeps its lines at most
 * [readable] long, so they sit in the middle (never all on the left with the rest blank), while the whole window still
 * scrolls it. [bottom]: [FabClearance] under a floating +.
 */
@Composable
fun ReadableList(
    modifier: Modifier,
    readable: Dp = 760.dp,
    bottom: Dp = 16.dp,
    list: @Composable (PaddingValues) -> Unit,
) {
    BoxWithConstraints(modifier) {
        val side = maxOf(16.dp, (maxWidth - readable) / 2)
        list(PaddingValues(start = side, end = side, top = 16.dp, bottom = bottom))
    }
}

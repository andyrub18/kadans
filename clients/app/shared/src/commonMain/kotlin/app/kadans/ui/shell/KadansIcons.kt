package app.kadans.ui.shell

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The app's few icons, drawn as 24-unit outlines (the navigation design's): no icon library to add, and `Icon` tints
 * them with the content color like any other.
 */
object KadansIcons {
    val Home = outline("Home", "M3 10.5 12 3l9 7.5V20a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1z")
    val Calendar = outline("Calendar", "M5 5h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2zM3 10h18M8 3v4M16 3v4")
    val Budget = outline(
        "Budget",
        "M17 7V5a1 1 0 0 0-1-1H5a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h14a1 1 0 0 0 1-1v-3" +
            "M3 6a2 2 0 0 0 2 2h14a1 1 0 0 1 1 1v3h-4a2 2 0 0 0 0 4h4",
    )
    val Focus = outline("Focus", "M20 13a8 8 0 1 1-16 0a8 8 0 1 1 16 0zM12 9v4l2.5 2.5M9.5 2h5")
    val Bell = outline("Bell", "M6 16v-5a6 6 0 1 1 12 0v5l2 2H4zM10 20.5a2 2 0 0 0 4 0")
    val Settings = outline(
        "Settings",
        "M15 12a3 3 0 1 1-6 0a3 3 0 1 1 6 0z" +
            "M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 " +
            "1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06" +
            "a2 2 0 1 1-2.83-2.83l.06-.06A1.65 1.65 0 0 0 4.68 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09" +
            "A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06A1.65 1.65 0 0 0 9 4.68" +
            "a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06" +
            "a2 2 0 1 1 2.83 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09" +
            "a1.65 1.65 0 0 0-1.51 1z",
    )
    val Add = outline("Add", "M12 5v14M5 12h14", width = 2f)
    val Back = outline("Back", "M19 12H5M11 18l-6-6 6-6", width = 2f)
    val Next = outline("Next", "M9 18l6-6-6-6", width = 2f)
    val Previous = outline("Previous", "M15 18l-6-6 6-6", width = 2f)

    private fun outline(name: String, path: String, width: Float = 1.8f): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .addPath(
                pathData = addPathNodes(path),
                stroke = SolidColor(Color.Black), // Icon tints it
                strokeLineWidth = width,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            .build()
}

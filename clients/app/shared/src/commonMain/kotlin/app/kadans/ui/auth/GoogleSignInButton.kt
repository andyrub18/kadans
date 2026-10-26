package app.kadans.ui.auth

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * "Continue with Google" as Google's branding guidelines draw it (developers.google.com/identity/branding-guidelines):
 * the standard four-colour G on the left, Google's light or dark fill, stroke and text colours (by the theme's surface),
 * 40 dp tall with the pill shape, 12 dp at the ends and 10 dp between the G and the words. The words are the app's
 * translation of "Continue with Google", one of the wordings Google allows. While the sign-in runs, a spinner takes the
 * words' place and the button takes no second tap.
 */
@Composable
internal fun GoogleSignInButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean,
    loading: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) GoogleDark else GoogleLight
    val clickable = enabled && !loading
    // Disabled as Google's own button is: the fill at 38%, the stroke at 12% of the text colour, the contents at 38%.
    val contents = if (enabled) 1f else 0.38f
    OutlinedButton(
        onClick = onClick,
        enabled = clickable,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, if (enabled) colors.stroke else colors.text.copy(alpha = 0.12f)),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = colors.fill,
            contentColor = colors.text,
            disabledContainerColor = if (enabled) colors.fill else colors.fill.copy(alpha = 0.38f),
            disabledContentColor = colors.text.copy(alpha = contents),
        ),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        // At least 40 dp: words that do not fit (a narrow screen, large system text) wrap and the button grows.
        modifier = modifier.heightIn(min = 40.dp),
    ) {
        Image(rememberVectorPainter(GoogleG), contentDescription = null, modifier = Modifier.size(20.dp).alpha(contents))
        Spacer(Modifier.width(10.dp))
        if (loading) {
            CircularProgressIndicator(color = colors.text, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
        } else {
            Text(text, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.25.sp, textAlign = TextAlign.Center)
        }
    }
}

private class GoogleColors(val fill: Color, val stroke: Color, val text: Color)

private val GoogleLight = GoogleColors(fill = Color(0xFFFFFFFF), stroke = Color(0xFF747775), text = Color(0xFF1F1F1F))
private val GoogleDark = GoogleColors(fill = Color(0xFF131314), stroke = Color(0xFF8E918F), text = Color(0xFFE3E3E3))

/** Google's "G", in its own colours: never tinted, recoloured or redrawn (the guidelines ask for it as it is). */
private val GoogleG: ImageVector =
    ImageVector.Builder("google", defaultWidth = 20.dp, defaultHeight = 20.dp, viewportWidth = 48f, viewportHeight = 48f)
        .addPath(
            addPathNodes("M24 9.5c3.54 0 6.71 1.22 9.21 3.6l6.85-6.85C35.9 2.38 30.47 0 24 0 14.62 0 6.51 5.38 2.56 13.22l7.98 6.19C12.43 13.72 17.74 9.5 24 9.5z"),
            fill = SolidColor(Color(0xFFEA4335)),
        )
        .addPath(
            addPathNodes("M46.98 24.55c0-1.57-.15-3.09-.38-4.55H24v9.02h12.94c-.58 2.96-2.26 5.48-4.78 7.18l7.73 6c4.51-4.18 7.09-10.36 7.09-17.65z"),
            fill = SolidColor(Color(0xFF4285F4)),
        )
        .addPath(
            addPathNodes("M10.53 28.59c-.48-1.45-.76-2.99-.76-4.59s.27-3.14.76-4.59l-7.98-6.19C.92 16.46 0 20.12 0 24c0 3.88.92 7.54 2.56 10.78l7.97-6.19z"),
            fill = SolidColor(Color(0xFFFBBC05)),
        )
        .addPath(
            addPathNodes("M24 48c6.48 0 11.93-2.13 15.89-5.81l-7.73-6c-2.15 1.45-4.92 2.3-8.16 2.3-6.26 0-11.57-4.22-13.47-9.91l-7.98 6.19C6.51 42.62 14.62 48 24 48z"),
            fill = SolidColor(Color(0xFF34A853)),
        )
        .build()

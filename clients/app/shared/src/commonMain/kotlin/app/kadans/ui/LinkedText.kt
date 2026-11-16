package app.kadans.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration

/**
 * Where the web links are in [text]: `http://` and `https://` addresses only, each up to the next space, without the
 * punctuation that ends the sentence around it ("see https://example.com/a." stops before the full stop; a closing
 * bracket stays when the address opened one). Nothing else is a link: no other scheme, no bare "example.com".
 */
internal fun linksIn(text: String): List<IntRange> =
    LINK.findAll(text).mapNotNull { match ->
        val start = match.range.first
        var end = match.range.last
        while (end > start && text[end] in TRAILING && !closesItsOwnBracket(text, start, end)) end--
        // "https://" with nothing after it is no address.
        val address = text.indexOf("://", start) + 3
        (start..end).takeIf { end >= address }
    }.toList()

/** The ")" at [end] closes a "(" that the address itself opened ("…/wiki/Kadans_(music)"): it is part of it. */
private fun closesItsOwnBracket(text: String, start: Int, end: Int): Boolean =
    text[end] == ')' && text.substring(start, end).let { before -> before.count { it == '(' } > before.count { it == ')' } }

private val LINK = Regex("https?://[^\\s<>\"]+", RegexOption.IGNORE_CASE)
private const val TRAILING = ".,;:!?)]}'’”»"

/**
 * A text the person wrote or shared (a todo's description), with its web links to tap: underlined, in the theme's
 * colour, opened by the system's browser. The link shown is the link opened: the address itself is the text.
 */
@Composable
fun LinkedText(text: String, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.bodyMedium, color: Color = Color.Unspecified) {
    val linkColor = MaterialTheme.colorScheme.primary
    val linked = remember(text, linkColor) {
        buildAnnotatedString {
            append(text)
            val styles = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
            for (range in linksIn(text)) {
                addLink(LinkAnnotation.Url(text.substring(range), styles), range.first, range.last + 1)
            }
        }
    }
    Text(linked, modifier = modifier, style = style, color = color)
}

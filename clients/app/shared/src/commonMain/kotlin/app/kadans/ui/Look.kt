package app.kadans.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kadans.resources.Res
import app.kadans.resources.baloo2_bold
import app.kadans.resources.baloo2_extrabold
import app.kadans.resources.nunito_bold
import app.kadans.resources.nunito_extrabold
import app.kadans.resources.nunito_medium
import app.kadans.resources.nunito_semibold
import org.jetbrains.compose.resources.Font

/*
 * Kadans' look (ARCHITECTURE → "The look"): a warm cream page, white cards with round corners, the logo's purple for
 * what one acts on and for focus, green for what is done and for breaks, amber for what nears a limit. Two families:
 * Baloo 2 for titles and large numbers, Nunito for everything read. Both are bundled (composeResources/font, their
 * licences in composeResources/files/licenses): a phone's own font would give every phone another Kadans.
 */

/** The logo's purple (branding/generate.py draws the icons with it). */
private val Purple = Color(0xFF6750A4)
private val PurpleDeep = Color(0xFF4A3688)
private val Lilac = Color(0xFFEEE8FA)
private val Cream = Color(0xFFFBF6EC)
private val Ink = Color(0xFF2B2233)
private val InkSoft = Color(0xFF675F70)

/**
 * Material's roles, light. Two are bent on purpose. Every container is white: cards, dialogs, menus and the bar sit
 * on the cream page as white shapes, with no grey steps between them. And `secondaryContainer` is the purple itself:
 * it is what Material paints a chosen chip and a chosen segment with, and a choice must be seen at a glance, which a
 * pale lilac beside white is not.
 */
internal val KadansLight = lightColorScheme(
    primary = Purple,
    onPrimary = Color.White,
    primaryContainer = Lilac,
    onPrimaryContainer = PurpleDeep,
    inversePrimary = Color(0xFFCDBDFF),
    secondary = PurpleDeep,
    onSecondary = Color.White,
    secondaryContainer = Purple,
    onSecondaryContainer = Color.White,
    tertiary = Color(0xFF0F7A52),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFDDF5EA),
    onTertiaryContainer = Color(0xFF0B5A3C),
    background = Cream,
    onBackground = Ink,
    surface = Cream,
    onSurface = Ink,
    surfaceVariant = Color(0xFFF1EADB),
    onSurfaceVariant = InkSoft,
    surfaceTint = Purple,
    inverseSurface = Ink,
    inverseOnSurface = Cream,
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    outline = Color(0xFF8B8494),
    outlineVariant = Color(0xFFE6DDCB),
    scrim = Color.Black,
    surfaceBright = Cream,
    surfaceDim = Color(0xFFE8E1D3),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color.White,
    surfaceContainerHighest = Color.White,
)

private val PurpleLight = Color(0xFFCDBDFF)
private val PurpleNight = Color(0xFF2A1A5E)
private val Night = Color(0xFF1D1724)
private val NightCard = Color(0xFF2A2333)
private val NightInk = Color(0xFFF4EEF8)
private val NightInkSoft = Color(0xFFB9AFC4)

/** Material's roles, dark: the same bends as [KadansLight], on a warm aubergine page. */
internal val KadansDark = darkColorScheme(
    primary = PurpleLight,
    onPrimary = PurpleNight,
    primaryContainer = Color(0xFF3B3152),
    onPrimaryContainer = Color(0xFFE3D9FF),
    inversePrimary = Purple,
    secondary = Color(0xFFE3D9FF),
    onSecondary = PurpleNight,
    secondaryContainer = PurpleLight,
    onSecondaryContainer = PurpleNight,
    tertiary = Color(0xFF5FD9A6),
    onTertiary = Color(0xFF0C3A27),
    tertiaryContainer = Color(0xFF17402F),
    onTertiaryContainer = Color(0xFFBFF2DA),
    background = Night,
    onBackground = NightInk,
    surface = Night,
    onSurface = NightInk,
    surfaceVariant = Color(0xFF352D40),
    onSurfaceVariant = NightInkSoft,
    surfaceTint = PurpleLight,
    inverseSurface = NightInk,
    inverseOnSurface = Night,
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    outline = Color(0xFF9A8FA8),
    outlineVariant = Color(0xFF40384B),
    scrim = Color.Black,
    surfaceBright = Color(0xFF3B3152),
    surfaceDim = Night,
    surfaceContainerLowest = Color(0xFF17121D),
    surfaceContainerLow = NightCard,
    surfaceContainer = NightCard,
    surfaceContainerHigh = Color(0xFF2F2839),
    surfaceContainerHighest = NightCard,
)

/**
 * What Kadans colours and Material has no role for. A pair is a fill and what is written on it; a lone colour is
 * text or a line on the page or on a card.
 */
@Immutable
class KadansColors(
    /** Done: a ticked circle, and what is written in it. */
    val done: Color,
    val onDone: Color,
    /** A break's share of a cycle's strip, beside the primary of its focus phases. */
    val breakTrack: Color,
    /** A phase still to come, in the strip. */
    val phaseAhead: Color,
    /** The track under a ring or a bar that fills with the primary. */
    val ringTrack: Color,
    /** The track under a limit's bar. */
    val barTrack: Color,
    /** A spending limit that is near (80%): its bar and its words. */
    val near: Color,
    /** "Pending": something waiting, neither done nor late. */
    val waiting: Color,
    val onWaiting: Color,
    /** Money out, where an arrow says so. */
    val expense: Color,
    val expenseContainer: Color,
    /**
     * A screen's one filled card (the balance): its purple and what is written on it, the lighter tile that sits on it
     * and what is written small. The primary itself by day; at night a deeper purple, where the primary, light there,
     * would be the brightest thing on the screen.
     */
    val hero: Color,
    val onHero: Color,
    val heroTile: Color,
    val onHeroSoft: Color,
    /** The circle of a todo not yet done. */
    val circle: Color,
    /** A card's soft shadow. */
    val shadow: Color,
) {
    companion object {
        val Light = KadansColors(
            done = Color(0xFF1FA971),
            onDone = Color.White,
            breakTrack = Color(0xFF9FDDBF),
            phaseAhead = Color(0xFFDCD3F0),
            ringTrack = Color(0xFFE7DFF7),
            barTrack = Color(0xFFEFE7D8),
            near = Color(0xFF8A5100),
            waiting = Color(0xFFFFF1CC),
            onWaiting = Color(0xFF7A5200),
            expense = Color(0xFFB4411F),
            expenseContainer = Color(0xFFFFE9DC),
            hero = Purple,
            onHero = Color.White,
            heroTile = Color(0xFF7A64B5),
            onHeroSoft = Color(0xFFE9E2FA),
            circle = Color(0xFFC9BEE6),
            shadow = Color(0x334A3688),
        )
        val Dark = KadansColors(
            done = Color(0xFF5FD9A6),
            onDone = Color(0xFF0C3A27),
            breakTrack = Color(0xFF3F8E6E),
            phaseAhead = Color(0xFF4A4160),
            ringTrack = Color(0xFF3B3152),
            barTrack = Color(0xFF3B3346),
            near = Color(0xFFFFB951),
            waiting = Color(0xFF4A3A10),
            onWaiting = Color(0xFFFFD98A),
            expense = Color(0xFFFFB59E),
            expenseContainer = Color(0xFF4A2A20),
            hero = Color(0xFF4B3B80),
            onHero = Color(0xFFF6F1FF),
            heroTile = Color(0xFF5D4C96),
            onHeroSoft = Color(0xFFD9CFF5),
            circle = Color(0xFF7A6E90),
            shadow = Color(0x66000000),
        )
    }
}

val LocalKadansColors = staticCompositionLocalOf { KadansColors.Light }

/** Round everywhere: a field 14, a chip a pill, a card 22, a sheet and a dialog 28. Buttons are Material's own pills. */
internal val KadansShapes = Shapes(
    extraSmall = RoundedCornerShape(14.dp),
    small = RoundedCornerShape(22.dp),
    medium = RoundedCornerShape(22.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * Titles and large numbers in Baloo 2, the rest in Nunito, a step heavier than Material's defaults: Nunito is a light
 * face, and at Material's 400 it greys out on the cream page. A weight nobody bundled falls to the nearest one.
 */
@Composable
internal fun kadansTypography(): Typography {
    val display = FontFamily(
        Font(Res.font.baloo2_bold, FontWeight.Bold),
        Font(Res.font.baloo2_extrabold, FontWeight.ExtraBold),
    )
    val text = FontFamily(
        Font(Res.font.nunito_medium, FontWeight.Medium),
        Font(Res.font.nunito_semibold, FontWeight.SemiBold),
        Font(Res.font.nunito_bold, FontWeight.Bold),
        Font(Res.font.nunito_extrabold, FontWeight.ExtraBold),
    )
    fun title(size: Int, line: Int, weight: FontWeight = FontWeight.ExtraBold) =
        TextStyle(fontFamily = display, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp)
    fun read(size: Int, line: Int, weight: FontWeight) =
        TextStyle(fontFamily = text, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp)
    return Typography(
        displayLarge = title(64, 68),
        displayMedium = title(48, 52),
        displaySmall = title(38, 42),
        headlineLarge = title(34, 38),
        headlineMedium = title(30, 34),
        headlineSmall = title(26, 30),
        titleLarge = title(24, 28),
        titleMedium = title(20, 26, FontWeight.Bold),
        titleSmall = read(16, 22, FontWeight.ExtraBold),
        bodyLarge = read(16, 24, FontWeight.SemiBold),
        bodyMedium = read(15, 21, FontWeight.SemiBold),
        bodySmall = read(13, 18, FontWeight.SemiBold),
        labelLarge = read(15, 20, FontWeight.ExtraBold),
        labelMedium = read(13, 16, FontWeight.ExtraBold),
        labelSmall = read(12, 16, FontWeight.Bold),
    )
}

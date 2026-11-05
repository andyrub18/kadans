package app.kadans.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koin.compose.koinInject

/** Light or dark: the system's (the default), or one chosen in Settings, on this device. */
enum class Appearance { System, Light, Dark }

/** This device's [Appearance], kept in its settings: a phone and a computer may well differ. */
class AppearancePreference(private val settings: Settings) {
    private val _appearance = MutableStateFlow(
        settings.getStringOrNull(KEY)?.let { name -> Appearance.entries.firstOrNull { it.name == name } } ?: Appearance.System,
    )
    val appearance: StateFlow<Appearance> = _appearance.asStateFlow()

    fun set(appearance: Appearance) {
        settings.putString(KEY, appearance.name)
        _appearance.value = appearance
    }

    private companion object {
        const val KEY = "kadans.appearance"
    }
}

/**
 * Whether the system is in dark mode: Compose's own answer where it has one (Android, iOS, Windows, macOS), the
 * desktop's settings portal on Linux, where Compose has none and always says light.
 */
@Composable
expect fun systemInDarkTheme(): Boolean

/** Tells the system bars (Android's status and navigation bars) whether their icons sit on a light or dark app. */
@Composable
expect fun SystemBarsFollow(dark: Boolean)

/** Whether Kadans is dark right now: what the screens ask, never the system directly (Settings may say otherwise). */
val LocalDarkTheme = staticCompositionLocalOf { false }

/**
 * Light or dark as the [Appearance] says, the system's by default, and as the system changes. Material 3 baseline
 * schemes for now; a Kadans palette is a deliberate later decision and slots in here.
 */
@Composable
fun KadansTheme(content: @Composable () -> Unit) {
    val appearance by koinInject<AppearancePreference>().appearance.collectAsState()
    val dark = when (appearance) {
        Appearance.System -> systemInDarkTheme()
        Appearance.Light -> false
        Appearance.Dark -> true
    }
    SystemBarsFollow(dark)
    CompositionLocalProvider(LocalDarkTheme provides dark) {
        MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme(), content = content)
    }
}

package app.kadans.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import app.kadans.desktop.LinuxColorScheme

/** Windows and macOS: Compose reads the system. Linux: the desktop's settings portal, followed as it changes. */
@Composable
actual fun systemInDarkTheme(): Boolean {
    val linux = LinuxColorScheme.session ?: return isSystemInDarkTheme()
    val dark by linux.dark.collectAsState()
    return dark
}

/** A desktop window has no system bars over it. */
@Composable
actual fun SystemBarsFollow(dark: Boolean) = Unit

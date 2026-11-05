package app.kadans.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable

@Composable
actual fun systemInDarkTheme(): Boolean = isSystemInDarkTheme()

/** iOS colours its status bar from the app's own appearance. */
@Composable
actual fun SystemBarsFollow(dark: Boolean) = Unit

package app.kadans.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import app.kadans.i18n.LanguageController
import app.kadans.i18n.StringsCatalog
import org.koin.compose.koinInject

/** The current language's catalog for UI living outside [App] — e.g. the desktop tray menu. */
@Composable
fun rememberAppStrings(): StringsCatalog {
    val language by koinInject<LanguageController>().language.collectAsState()
    return language.catalog
}

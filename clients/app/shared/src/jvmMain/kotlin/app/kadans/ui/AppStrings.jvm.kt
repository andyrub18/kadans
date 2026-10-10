package app.kadans.ui

import app.kadans.i18n.LanguageController
import app.kadans.i18n.StringsCatalog
import org.koin.core.context.GlobalContext

/** The current language's catalog outside any composition: the desktop tray's first labels, before the window opens. */
fun currentAppStrings(): StringsCatalog = GlobalContext.get().get<LanguageController>().language.value.catalog

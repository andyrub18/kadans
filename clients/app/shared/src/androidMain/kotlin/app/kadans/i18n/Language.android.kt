package app.kadans.i18n

actual fun systemLanguageTag(): String = java.util.Locale.getDefault().language

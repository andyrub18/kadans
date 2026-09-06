package app.kadans.i18n

enum class Language(val tag: String, val displayName: String) {
    En("en", "English"),
    Fr("fr", "Français"),
    Ht("ht", "Kreyòl Ayisyen");

    val catalog: StringsCatalog
        get() = when (this) {
            En -> EnglishStrings
            Fr -> FrenchStrings
            Ht -> CreoleStrings
        }

    companion object {
        fun fromTag(tag: String?): Language =
            entries.firstOrNull { it.tag == tag?.lowercase()?.take(2) } ?: En

        fun next(current: Language): Language = entries[(current.ordinal + 1) % entries.size]
    }
}

/** The device's UI language tag ("fr", "ht", …), used only as the first-run default. */
expect fun systemLanguageTag(): String

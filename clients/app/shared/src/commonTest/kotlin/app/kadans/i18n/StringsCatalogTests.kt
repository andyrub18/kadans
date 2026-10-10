package app.kadans.i18n

import app.kadans.api.model.Frequency
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class StringsCatalogTests {
    @Test
    fun errorsAreLocalizedByCodeWithServerFallback() {
        assertEquals("Invalid username or password.", EnglishStrings.errorFor("10001", null))
        assertEquals("Nom d'utilisateur ou mot de passe invalide.", FrenchStrings.errorFor("10001", null))
        assertEquals("Non itilizatè oswa modpas la pa bon.", CreoleStrings.errorFor("10001", null))
        assertEquals("Nou pa ka jwenn sèvè a.", CreoleStrings.errorFor("network", "ignored"))
        // Unknown codes fall back to the server's detail, then to the generic text.
        assertEquals("server detail", FrenchStrings.errorFor("99999", "server detail"))
        assertEquals(EnglishStrings.errGeneric, EnglishStrings.errorFor("99999", null))
    }

    @Test
    fun intervalSentencesFollowEachLanguagesGrammar() {
        assertEquals("Every day", EnglishStrings.repeat.every(Frequency.Daily, 1))
        assertEquals("Every 2 hours", EnglishStrings.repeat.every(Frequency.Hourly, 2))
        assertEquals("Every 3 weeks", EnglishStrings.repeat.every(Frequency.Weekly, 3))
        // French switches determiner between singular and plural, and agrees with the unit's gender.
        assertEquals("Chaque jour", FrenchStrings.repeat.every(Frequency.Daily, 1))
        assertEquals("Tous les 2 jours", FrenchStrings.repeat.every(Frequency.Daily, 2))
        assertEquals("Toutes les 2 heures", FrenchStrings.repeat.every(Frequency.Hourly, 2))
        assertEquals("Toutes les 2 semaines", FrenchStrings.repeat.every(Frequency.Weekly, 2))
        assertEquals("Chaque année", FrenchStrings.repeat.every(Frequency.Yearly, 1))
        // Creole nouns don't inflect for number.
        assertEquals("Chak jou", CreoleStrings.repeat.every(Frequency.Daily, 1))
        assertEquals("Chak 2 jou", CreoleStrings.repeat.every(Frequency.Daily, 2))
    }

    @Test
    fun languageTagsResolveWithRegionAndUnknownFallback() {
        assertEquals(Language.Fr, Language.fromTag("fr-FR"))
        assertEquals(Language.Ht, Language.fromTag("HT"))
        assertEquals(Language.En, Language.fromTag("de"))
        assertEquals(Language.En, Language.fromTag(null))
        assertEquals(Language.Fr, Language.next(Language.En))
        assertEquals(Language.En, Language.next(Language.Ht))
    }

    @Test
    fun catalogsAreCompleteAndDistinct() {
        // The data-class constructor already forces every key at compile time; here we
        // catch blank values and whole-catalog copy-paste between languages.
        for (language in Language.entries) {
            val printed = language.catalog.toString()
            assertFalse(printed.contains("=,") || printed.contains("=)"), "${language.tag} has a blank string")
        }
        assertNotEquals(EnglishStrings, FrenchStrings)
        assertNotEquals(FrenchStrings, CreoleStrings)
        assertNotEquals(CreoleStrings, EnglishStrings)
    }
}

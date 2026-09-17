package app.kadans.i18n

import kotlin.test.Test
import kotlin.test.assertTrue

class StringsCatalogSizeTest {
    /**
     * The JVM allows 255 parameter slots per method. A data class with N String properties has a
     * constructor of N and a synthetic `copy$default` of N + instance + one int mask per 32 + marker;
     * beyond that the class compiles and then fails to load (ClassFormatError) — the app would not
     * start. Fail here first, with a message that says what to do.
     */
    @Test
    fun the_flat_catalog_stays_clear_of_the_jvm_parameter_limit() {
        val widest = (StringsCatalog::class.java.declaredMethods.map { it.parameterCount } +
            StringsCatalog::class.java.declaredConstructors.map { it.parameterCount }).max()

        assertTrue(
            widest <= 250,
            "StringsCatalog's widest method takes $widest parameters (JVM limit: 255). " +
                "Put new strings in a feature group like FocusStatsStrings instead of the flat catalog.",
        )
    }
}

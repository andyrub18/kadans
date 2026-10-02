package app.kadans.profile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.datetime.UtcOffset

class TimeZoneCatalogTests {
    private val july = Instant.parse("2027-07-01T12:00:00Z")
    private val january = Instant.parse("2027-01-15T12:00:00Z")

    @Test
    fun only_region_city_zones_and_utc_can_be_picked() {
        listOf("UTC", "America/Port-au-Prince", "America/Argentina/Buenos_Aires", "Europe/Paris", "Pacific/Auckland")
            .forEach { assertTrue(TimeZoneCatalog.isSelectable(it), it) }
        listOf("Etc/GMT+4", "GMT", "EST5EDT", "US/Eastern", "Canada/Atlantic", "GMT-04:00", "SystemV/AST4", "")
            .forEach { assertFalse(TimeZoneCatalog.isSelectable(it), it) }
    }

    @Test
    fun the_list_leaves_out_fixed_offsets_and_is_sorted_by_city() {
        val entries = TimeZoneCatalog.entries(july)

        assertTrue(entries.size > 300, "only ${entries.size} zones")
        assertTrue(entries.none { it.id.startsWith("Etc/") || it.id.startsWith("US/") })
        assertTrue(entries.any { it.id == "America/Port-au-Prince" })
        assertEquals(entries.map { it.city.lowercase() }, entries.map { it.city.lowercase() }.sorted())
        // An id the platform does not know is dropped instead of breaking the list.
        assertEquals(listOf("Europe/Paris"), TimeZoneCatalog.entries(july, listOf("Europe/Paris", "Mars/Olympus_Mons")).map { it.id })
    }

    @Test
    fun an_entry_names_the_city_its_region_and_the_offset_of_the_moment() {
        assertEquals(
            TimeZoneEntry("America/Port-au-Prince", "Port-au-Prince", "America", "UTC−4"),
            TimeZoneCatalog.entry("America/Port-au-Prince", july),
        )
        assertEquals("UTC−5", TimeZoneCatalog.entry("America/Port-au-Prince", january).offset)
        assertEquals(
            TimeZoneEntry("America/Argentina/Buenos_Aires", "Buenos Aires", "America · Argentina", "UTC−3"),
            TimeZoneCatalog.entry("America/Argentina/Buenos_Aires", july),
        )
        assertEquals(TimeZoneEntry("UTC", "UTC", "", "UTC"), TimeZoneCatalog.entry("UTC", july))
    }

    @Test
    fun offsets_read_as_people_write_them() {
        assertEquals("UTC", TimeZoneCatalog.offsetLabel(UtcOffset(hours = 0)))
        assertEquals("UTC+1", TimeZoneCatalog.offsetLabel(UtcOffset(hours = 1)))
        assertEquals("UTC−4", TimeZoneCatalog.offsetLabel(UtcOffset(hours = -4)))
        assertEquals("UTC+5:30", TimeZoneCatalog.offsetLabel(UtcOffset(hours = 5, minutes = 30)))
        assertEquals("UTC−9:30", TimeZoneCatalog.offsetLabel(UtcOffset(hours = -9, minutes = -30)))
        assertEquals("UTC+12:45", TimeZoneCatalog.offsetLabel(UtcOffset(hours = 12, minutes = 45)))
    }

    @Test
    fun search_matches_words_of_the_city_and_region_or_an_offset() {
        val portAuPrince = TimeZoneCatalog.entry("America/Port-au-Prince", july)
        val newYork = TimeZoneCatalog.entry("America/New_York", july)
        val kolkata = TimeZoneCatalog.entry("Asia/Kolkata", july)

        assertTrue(TimeZoneCatalog.matches(portAuPrince, ""))
        assertTrue(TimeZoneCatalog.matches(portAuPrince, "port au"))
        assertTrue(TimeZoneCatalog.matches(portAuPrince, "PORT-AU-PRINCE"))
        assertTrue(TimeZoneCatalog.matches(portAuPrince, "america"))
        assertTrue(TimeZoneCatalog.matches(newYork, "new york"))
        assertTrue(TimeZoneCatalog.matches(newYork, "new-york"))
        assertFalse(TimeZoneCatalog.matches(newYork, "paris"))

        assertTrue(TimeZoneCatalog.matches(portAuPrince, "utc-4"))
        assertTrue(TimeZoneCatalog.matches(portAuPrince, "UTC−4"))
        assertTrue(TimeZoneCatalog.matches(kolkata, "+5:30"))
        assertTrue(TimeZoneCatalog.matches(kolkata, "utc+5"))
        assertFalse(TimeZoneCatalog.matches(kolkata, "utc-4"))
        // "UTC−1" is the Azores in winter, not Honolulu's UTC−10.
        assertTrue(TimeZoneCatalog.matches(TimeZoneCatalog.entry("Atlantic/Azores", january), "utc-1"))
        assertFalse(TimeZoneCatalog.matches(TimeZoneCatalog.entry("Pacific/Honolulu", january), "utc-1"))
        // Punctuation alone is no query at all.
        assertTrue(TimeZoneCatalog.matches(kolkata, "-"))
    }

    @Test
    fun a_device_that_only_says_utc_or_an_offset_is_not_followed() {
        assertEquals("America/Port-au-Prince", usableDeviceZone("America/Port-au-Prince"))
        assertNull(usableDeviceZone("UTC"))
        assertNull(usableDeviceZone("GMT-04:00"))
        assertNull(usableDeviceZone("Etc/GMT+4"))
    }
}

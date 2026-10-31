package app.kadans.updates

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The desktop's update check, as it reads the site's latest.json (ARCHITECTURE → "The desktop app: in the tray, once"). */
class AppUpdatesTests {
    private fun latest(version: String, minimum: String? = null) =
        """{"version":"$version"${minimum?.let { ""","minimumVersion":"$it"""" } ?: ""},""" +
            """"notes":{"en":"Faster reminders.","fr":"Rappels plus rapides."}}"""

    @Test
    fun versions_compare_as_numbers() {
        assertTrue(AppUpdates.isNewer("1.0.1", "1.0.0"))
        assertTrue(AppUpdates.isNewer("1.10.0", "1.9.9"))
        assertTrue(AppUpdates.isNewer("2.0.0-beta", "1.9.0"))
        assertFalse(AppUpdates.isNewer("1.0.0", "1.0.0"))
        assertFalse(AppUpdates.isNewer("0.9.9", "1.0.0"))
        // Not versions: never newer, so a broken file never nags.
        assertFalse(AppUpdates.isNewer("latest", "1.0.0"))
        assertFalse(AppUpdates.isNewer("1.1", "1.0.0"))
        assertFalse(AppUpdates.isNewer("1.1.-1", "1.0.0"))
    }

    @Test
    fun this_build_has_a_version() {
        assertNotNull(AppUpdates.parse(AppUpdates().currentVersion))
    }

    @Test
    fun a_newer_version_is_a_notice_in_the_apps_language() {
        val updates = AppUpdates(currentVersion = "1.0.0")

        assertEquals(UpdateNotice("1.1.0", required = false, notes = "Rappels plus rapides."), updates.read(latest("1.1.0"), "fr"))
        assertEquals("Faster reminders.", updates.read(latest("1.1.0"), "ht")?.notes, "no Kreyòl notes: English")
        assertEquals(updates.available.value?.version, "1.1.0")
    }

    @Test
    fun the_same_version_or_an_older_one_is_no_notice() {
        val updates = AppUpdates(currentVersion = "1.1.0")

        assertNull(updates.read(latest("1.1.0"), "en"))
        assertNull(updates.read(latest("1.0.5"), "en"))
        assertNull(updates.available.value)
    }

    @Test
    fun older_than_the_oldest_that_works_must_update() {
        val updates = AppUpdates(currentVersion = "1.0.0")

        assertTrue(updates.read(latest("1.2.0", minimum = "1.1.0"), "en")!!.required)
        assertFalse(updates.read(latest("1.2.0", minimum = "1.0.0"), "en")!!.required)
    }

    @Test
    fun offline_or_a_broken_file_keeps_what_was_known() {
        val updates = AppUpdates(currentVersion = "1.0.0")
        updates.read(latest("1.1.0"), "en")

        assertEquals("1.1.0", updates.read(null, "en")?.version)
        assertEquals("1.1.0", updates.read("<html>not found</html>", "en")?.version)
        assertEquals("1.1.0", updates.read("x".repeat(AppUpdates.MAX_BODY + 1), "en")?.version)
        assertEquals("1.1.0", updates.available.value?.version)
    }

    @Test
    fun a_version_is_announced_once() {
        val notice = UpdateNotice("1.1.0", required = false, notes = null)

        assertEquals(notice, AppUpdates.toAnnounce(notice, lastAnnounced = null))
        assertEquals(notice, AppUpdates.toAnnounce(notice, lastAnnounced = "1.0.5"))
        assertNull(AppUpdates.toAnnounce(notice, lastAnnounced = "1.1.0"))
        assertNull(AppUpdates.toAnnounce(null, lastAnnounced = null))
    }
}

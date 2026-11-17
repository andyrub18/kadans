package app.kadans.share

import app.kadans.api.TodosApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A text another app shared, read as the start of a todo (ARCHITECTURE → "Share to Kadans"). */
class SharedTextTests {
    @Test
    fun a_line_is_the_title_and_needs_no_description() {
        assertEquals(TodoDraft("Buy rice and oil", ""), SharedText.draft(null, "  Buy rice and oil \n"))
    }

    @Test
    fun a_page_shared_from_a_browser_is_its_title_and_its_link() {
        // Chrome: the page's title as the subject, its address as the text.
        assertEquals(
            TodoDraft("How to read a balance sheet", "https://www.example.com/articles/balance-sheet?ref=share"),
            SharedText.draft("How to read a balance sheet", "https://www.example.com/articles/balance-sheet?ref=share"),
        )
    }

    @Test
    fun a_link_alone_is_titled_by_its_site() {
        // No title came with it: the site's name reads better at the top of a todo than the address.
        assertEquals(
            TodoDraft("example.com", "https://www.example.com/articles/balance-sheet"),
            SharedText.draft(null, "https://www.example.com/articles/balance-sheet"),
        )
        assertEquals("example.ht", SharedText.draft("", "HTTPS://example.ht/")?.title)
    }

    @Test
    fun a_message_gives_its_first_line_as_the_title_and_stays_whole_in_the_description() {
        val message = "Meeting moved to Thursday\n\nBring the signed forms and the two photos.\nRoom 4, second floor."
        assertEquals(TodoDraft("Meeting moved to Thursday", message), SharedText.draft(null, message))
    }

    @Test
    fun a_long_first_line_is_cut_at_a_word_and_kept_whole_below() {
        val line = "Remember to call the electricity company about the bill that came twice this month and ask them to cancel the second one before Friday"
        val draft = SharedText.draft(null, line)!!
        assertTrue(draft.title.length <= SharedText.SUGGESTED_TITLE, draft.title)
        assertTrue(draft.title.endsWith("…"))
        assertFalse(draft.title.dropLast(1).endsWith(" "))
        assertTrue(line.startsWith(draft.title.dropLast(1)), "cut at a word, nothing rewritten")
        assertEquals(line, draft.description)
    }

    @Test
    fun a_subject_that_only_repeats_the_text_is_said_once() {
        assertEquals(TodoDraft("Pay the school", ""), SharedText.draft("Pay the school", "Pay the school"))
    }

    @Test
    fun nothing_shared_is_no_draft() {
        assertNull(SharedText.draft(null, null))
        assertNull(SharedText.draft("  ", "\n\t ​"))
    }

    @Test
    fun what_is_not_text_is_left_out() {
        // Windows line ends, tabs, a control character, a zero-width space and a right-to-left override (which can
        // make a text read as another): none of them reaches a title.
        val draft = SharedText.draft("Pay\u0007 the‮ school\r\n", "one\r\ntwo\ttabbed\r\n\r\n\r\n\r\nthree​")!!
        assertEquals("Pay the school", draft.title)
        assertEquals("one\ntwo tabbed\n\nthree", draft.description)
    }

    @Test
    fun a_draft_never_exceeds_what_a_todo_holds() {
        val huge = "word ".repeat(50_000)
        val draft = SharedText.draft("s".repeat(5_000), huge)!!
        assertTrue(draft.title.length <= TodosApi.TITLE_LENGTH)
        assertTrue(draft.title.length <= SharedText.SUGGESTED_TITLE)
        assertTrue(draft.description.length in (TodosApi.DESCRIPTION_LENGTH - 30)..TodosApi.DESCRIPTION_LENGTH, "${draft.description.length}")
        assertTrue(draft.description.endsWith("…"))
    }

    @Test
    fun a_cut_never_falls_in_the_middle_of_an_emoji() {
        // "🎉" takes two characters: cut between them, the text would end in half a character.
        val text = "a".repeat(9) + "🎉" + "b"
        assertEquals("a".repeat(9), SharedText.within(text, 10))
        assertEquals("a".repeat(9) + "🎉", SharedText.within(text, 11))
        assertEquals(text, SharedText.within(text, 12))
    }
}

package app.kadans.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/** Which parts of a todo's description open the browser when tapped. */
class LinkedTextTests {
    private fun links(text: String) = linksIn(text).map { text.substring(it) }

    @Test
    fun a_web_address_is_a_link_up_to_the_next_space() {
        assertEquals(listOf("https://example.com/a?b=1&c=2#top"), links("Read https://example.com/a?b=1&c=2#top tonight"))
        assertEquals(listOf("http://example.com", "HTTPS://Example.com/x"), links("http://example.com\nand HTTPS://Example.com/x"))
    }

    @Test
    fun the_punctuation_of_the_sentence_around_it_is_not_part_of_it() {
        assertEquals(listOf("https://example.com/a"), links("See https://example.com/a."))
        assertEquals(listOf("https://example.com/a"), links("(https://example.com/a), then call."))
        assertEquals(listOf("https://example.com/a"), links("« https://example.com/a »"))
        assertEquals(listOf("https://example.com/a"), links("Is it https://example.com/a?!"))
        // A bracket the address opened itself stays.
        assertEquals(listOf("https://en.wikipedia.org/wiki/Kadans_(music)"), links("https://en.wikipedia.org/wiki/Kadans_(music)."))
    }

    @Test
    fun nothing_else_is_a_link() {
        assertEquals(emptyList(), links("example.com, www.example.com, mailto:a@b.ht, tel:+50912345678"))
        assertEquals(emptyList(), links("javascript:alert(1) file:///etc/passwd kadans://todos/new intent://x"))
        assertEquals(emptyList(), links("https:// and http://."))
    }
}

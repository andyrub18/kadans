package app.kadans.ui

import app.kadans.api.KadansApi
import app.kadans.share.TodoDraft
import app.kadans.ui.todos.CreateTodoViewModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** The form for a new todo, opened by a text another app shared (ARCHITECTURE → "Share to Kadans"). */
class SharedTodoFormTests {
    @Test
    fun the_form_starts_from_what_was_shared_and_saves_nothing_by_itself() {
        val form = CreateTodoViewModel(KadansApi.create("http://test"), TodoDraft("How to read a balance sheet", "https://example.com/a")).state.value

        assertEquals("How to read a balance sheet", form.title)
        assertEquals("https://example.com/a", form.description)
        // Everything else is the form's own start: the person still says when, and presses "Create".
        assertEquals(CreateTodoViewModel(KadansApi.create("http://test")).state.value.copy(title = form.title, description = form.description), form)
        assertFalse(form.isLoading)
    }
}

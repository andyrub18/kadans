package app.kadans.ui

import app.kadans.share.TodoDraft
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class DeepLinkTests {
    @Test
    fun theEmailedResetLinkCarriesEmailAndDecodedToken() {
        val route = parseDeepLink("https://api.kadansplanning.com/auth/reset-password?email=a%40b.ht&token=CfDJ8-abc_123")
        assertEquals(ResetPasswordRoute("a@b.ht", "CfDJ8-abc_123"), route)
    }

    @Test
    fun aReminderOpensItsTodo() {
        val id = "3f2b8c1e-9a4d-4e6b-8c2a-7d5e1f0a9b3c"
        assertEquals(TodoDetailRoute(id), parseDeepLink(todoLink(id)))
        assertEquals(TodoDetailRoute(id), parseDeepLink(todoLink(id.uppercase())), "the id as the app keeps it")
        assertNull(parseDeepLink("kadans://todos/not-an-id"))
        assertNull(parseDeepLink("kadans://todos/$id/../../users/me"))
        assertNull(parseDeepLink("kadans://todos/"))
    }

    @Test
    fun aTextSharedByAnotherAppOpensTheFormFilledIn() {
        val draft = TodoDraft("Réunion & budget: 50% + café ?", "Ligne 1\nhttps://example.com/a?b=1&c=2#top")
        assertEquals(SharedTodoRoute(draft.title, draft.description), parseDeepLink(sharedTodoLink(draft)))
        // A title alone, a description alone.
        assertEquals(SharedTodoRoute("Pay the school", ""), parseDeepLink(sharedTodoLink(TodoDraft("Pay the school", ""))))
        assertEquals(SharedTodoRoute("", "notes"), parseDeepLink(sharedTodoLink(TodoDraft("", "notes"))))
        // Nothing in it: the plain form, as the widget's "+".
        assertEquals(CreateTodoRoute, parseDeepLink(NEW_TODO_LINK))
        assertEquals(CreateTodoRoute, parseDeepLink("$NEW_TODO_LINK?title=&description=%20"))
    }

    @Test
    fun aSharedTodoLinkFromAnywhereIsCutToWhatATodoHolds() {
        // The app makes these links itself, but the activity that takes them takes them from any app.
        val route = parseDeepLink("kadans://todos/new?title=${"t".repeat(900)}&description=${"d".repeat(9000)}") as SharedTodoRoute
        assertEquals(500, route.title.length)
        assertEquals(4000, route.description.length)
        // It needs a session like any todo, and opens nothing else.
        assertFalse(opensWith(route, signedIn = false))
        assertTrue(opensWith(route, signedIn = true))
        assertNull(parseDeepLink("kadans://todos/new/extra?title=x"))
    }

    @Test
    fun aLinkThatArrivesBeforeTheNavigationListensIsKept() = runTest {
        // Android restarting a stopped app for a tapped reminder hands the link over before any screen listens.
        IncomingLinks.open(todoLink("3f2b8c1e-9a4d-4e6b-8c2a-7d5e1f0a9b3c"))
        assertEquals(TodoDetailRoute("3f2b8c1e-9a4d-4e6b-8c2a-7d5e1f0a9b3c"), parseDeepLink(IncomingLinks.links.first()))
    }

    @Test
    fun aFocusSessionStartedFromAReminderOpensItWithoutStartingAnother() {
        val id = "3f2b8c1e-9a4d-4e6b-8c2a-7d5e1f0a9b3c"
        assertEquals(PomodoroRoute(id, startsOne = false), parseDeepLink(focusLink(id)))
        assertNull(parseDeepLink("kadans://todos/$id/focus/again"))
        assertNull(parseDeepLink("kadans://todos/$id/elsewhere"))
        assertFalse(opensWith(PomodoroRoute(id, startsOne = false), signedIn = false))
    }

    @Test
    fun theWidgetsPlusOpensTheFormForANewTodoWithASessionOnly() {
        assertEquals(CreateTodoRoute, parseDeepLink(NEW_TODO_LINK))
        assertTrue(opensWith(CreateTodoRoute, signedIn = true))
        assertFalse(opensWith(CreateTodoRoute, signedIn = false))
        // "new" is the whole path: nothing rides along with it.
        assertNull(parseDeepLink("kadans://todos/new/focus"))
        assertNull(parseDeepLink("kadans://todos/newer"))
    }

    @Test
    fun theWidgetsHeaderOpensHomeWithASessionOnly() {
        assertEquals(HomeRoute, parseDeepLink(HOME_LINK))
        assertTrue(opensWith(HomeRoute, signedIn = true))
        // Signed out, the app opens as usual, on the sign-in screen: Home is never put over it.
        assertFalse(opensWith(HomeRoute, signedIn = false))
        assertNull(parseDeepLink("kadans://home/anything"))
    }

    @Test
    fun aLimitAlertOpensTheBudget() {
        assertEquals(BudgetRoute, parseDeepLink(BUDGET_LINK))
        assertEquals(null, parseDeepLink("kadans://budget/anything"))
        assertTrue(opensWith(BudgetRoute, signedIn = true))
        assertFalse(opensWith(BudgetRoute, signedIn = false))
    }

    @Test
    fun aTodoOpensOnlyWithASession() {
        assertTrue(opensWith(TodoDetailRoute("x"), signedIn = true))
        assertFalse(opensWith(TodoDetailRoute("x"), signedIn = false))
        assertTrue(opensWith(ResetPasswordRoute("a@b.ht", "t"), signedIn = false))
    }

    @Test
    fun unknownOrMalformedLinksOpenTheAppNormally() {
        assertNull(parseDeepLink(null))
        assertNull(parseDeepLink(""))
        assertNull(parseDeepLink("kadans://auth/reset-password?email=a&token=b"), "a custom scheme any app can claim")
        assertNull(parseDeepLink("https://auth/reset-password?email=a&token=b"))
        assertNull(parseDeepLink("https://evil.example/auth/reset-password?email=a&token=b"))
        assertNull(parseDeepLink("http://api.kadansplanning.com/auth/reset-password?email=a&token=b"))
        assertNull(parseDeepLink("https://api.kadansplanning.com/auth/confirm-email?userId=a&token=b"))
        assertNull(parseDeepLink("https://api.kadansplanning.com/auth/reset-password?email=a")) // token missing
        assertNull(parseDeepLink("::not a url::"))
    }
}

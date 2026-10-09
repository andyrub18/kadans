package app.kadans.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class DeepLinkTests {
    @Test
    fun resetPasswordLinkCarriesEmailAndDecodedToken() {
        val route = parseDeepLink("kadans://auth/reset-password?email=a%40b.ht&token=CfDJ8-abc_123")
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
    fun aLinkThatArrivesBeforeTheNavigationListensIsKept() = runTest {
        // Android restarting a stopped app for a tapped reminder hands the link over before any screen listens.
        IncomingLinks.open(todoLink("3f2b8c1e-9a4d-4e6b-8c2a-7d5e1f0a9b3c"))
        assertEquals(TodoDetailRoute("3f2b8c1e-9a4d-4e6b-8c2a-7d5e1f0a9b3c"), parseDeepLink(IncomingLinks.links.first()))
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
        assertNull(parseDeepLink("kadans://auth/unknown-path?x=1"))
        assertNull(parseDeepLink("kadans://other/reset-password?email=a&token=b"))
        assertNull(parseDeepLink("https://auth/reset-password?email=a&token=b"))
        assertNull(parseDeepLink("kadans://auth/reset-password?email=a")) // token missing
        assertNull(parseDeepLink("::not a url::"))
    }
}

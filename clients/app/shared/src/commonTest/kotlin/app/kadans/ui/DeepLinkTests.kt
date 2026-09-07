package app.kadans.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeepLinkTests {
    @Test
    fun resetPasswordLinkCarriesEmailAndDecodedToken() {
        val route = parseDeepLink("kadans://auth/reset-password?email=a%40b.ht&token=CfDJ8-abc_123")
        assertEquals(ResetPasswordRoute("a@b.ht", "CfDJ8-abc_123"), route)
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

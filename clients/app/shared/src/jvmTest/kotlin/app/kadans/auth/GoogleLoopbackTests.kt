package app.kadans.auth

import app.kadans.api.model.GoogleProviderResponse
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLDecoder
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class GoogleLoopbackTests {
    private fun queryOf(url: String): Map<String, String> =
        url.substringAfter('?').split('&').associate {
            it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8)
        }

    @Test
    fun pkce_challenge_matches_the_rfc_7636_example() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            GoogleLoopback.challengeOf("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test
    fun verifiers_are_long_enough_and_never_repeat() {
        val first = GoogleLoopback.newVerifier()
        assertTrue(first.length in 43..128, "RFC 7636 wants 43–128 characters, got ${first.length}")
        assertTrue(first != GoogleLoopback.newVerifier())
    }

    @Test
    fun authorization_url_asks_for_an_id_token_with_pkce() {
        val query = queryOf(GoogleLoopback.authorizationUrl("desktop-id", "http://127.0.0.1:5000", "chal", "st"))

        assertEquals("desktop-id", query["client_id"])
        assertEquals("http://127.0.0.1:5000", query["redirect_uri"])
        assertEquals("code", query["response_type"])
        assertTrue("openid" in query.getValue("scope").split(' '), "without openid Google returns no id_token")
        assertEquals("chal", query["code_challenge"])
        assertEquals("S256", query["code_challenge_method"])
        assertEquals("st", query["state"])
    }

    @Test
    fun callback_parsing_only_trusts_our_own_state() {
        assertEquals(GoogleLoopback.Callback.Code("4/abc"), GoogleLoopback.parseCallback("GET /?state=st&code=4%2Fabc HTTP/1.1", "st"))
        assertEquals(GoogleLoopback.Callback.Denied("access_denied"), GoogleLoopback.parseCallback("GET /?error=access_denied&state=st HTTP/1.1", "st"))
        assertEquals(GoogleLoopback.Callback.Ignore, GoogleLoopback.parseCallback("GET /?state=other&code=x HTTP/1.1", "st"))
        assertEquals(GoogleLoopback.Callback.Ignore, GoogleLoopback.parseCallback("GET /favicon.ico HTTP/1.1", "st"))
        assertEquals(GoogleLoopback.Callback.Ignore, GoogleLoopback.parseCallback(null, "st"))
    }

    /** A fake browser: follows the consent URL's redirect_uri the way Google would. */
    private fun browser(reply: (redirectUri: String, state: String) -> List<String>): (String) -> Unit = { url ->
        val query = queryOf(url)
        thread {
            reply(query.getValue("redirect_uri"), query.getValue("state")).forEach { target ->
                runCatching {
                    (URI(target).toURL().openConnection() as HttpURLConnection).run { responseCode; disconnect() }
                }
            }
        }
    }

    @Test
    fun the_whole_loopback_flow_returns_the_code_with_its_verifier() = runBlocking {
        var page = ""
        val signIn = JvmGoogleSignIn(
            returnToAppText = { "Back to Kadans <now>" },
            openBrowser = { url ->
                val query = queryOf(url)
                thread {
                    val redirect = query.getValue("redirect_uri")
                    // noise first: the listener must survive it and keep waiting
                    runCatching { (URI("$redirect/favicon.ico").toURL().openConnection() as HttpURLConnection).responseCode }
                    val connection = URI("$redirect/?state=${query.getValue("state")}&code=the-code").toURL().openConnection() as HttpURLConnection
                    page = connection.inputStream.bufferedReader().readText()
                }
            },
            timeoutMillis = 10_000,
        )

        val credential = signIn.signIn(GoogleProviderResponse(desktopClientId = "desktop-id"))

        val code = assertIs<GoogleCredential.AuthorizationCode>(credential)
        assertEquals("the-code", code.code)
        assertTrue(code.redirectUri.startsWith("http://127.0.0.1:"))
        assertTrue(code.codeVerifier.length >= 43)
        Thread.sleep(200) // let the fake browser finish reading the page
        assertTrue("Back to Kadans &lt;now&gt;" in page, "the page is localized and escaped: $page")
    }

    @Test
    fun cancelling_on_googles_page_is_not_an_error() = runBlocking {
        val signIn = JvmGoogleSignIn(
            returnToAppText = { "" },
            openBrowser = browser { redirect, state -> listOf("$redirect/?error=access_denied&state=$state") },
            timeoutMillis = 10_000,
        )

        assertNull(signIn.signIn(GoogleProviderResponse(desktopClientId = "desktop-id")))
    }

    @Test
    fun nobody_finishing_the_flow_times_out_as_a_cancel() = runBlocking {
        val signIn = JvmGoogleSignIn(returnToAppText = { "" }, openBrowser = {}, timeoutMillis = 300)

        assertNull(signIn.signIn(GoogleProviderResponse(desktopClientId = "desktop-id")))
    }

    @Test
    fun only_a_published_desktop_client_id_enables_the_button() {
        val signIn = JvmGoogleSignIn(returnToAppText = { "" })

        assertTrue(signIn.canSignIn(GoogleProviderResponse(desktopClientId = "id")))
        assertTrue(!signIn.canSignIn(GoogleProviderResponse(webClientId = "web-only")))
    }
}

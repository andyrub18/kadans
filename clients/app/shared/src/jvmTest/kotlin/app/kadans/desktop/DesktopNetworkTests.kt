@file:Suppress("FunctionName")

package app.kadans.desktop

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.ServerSocket
import java.net.SocketAddress
import java.net.URI
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class DesktopNetworkTests {
    /** A company proxy in miniature: answers one request, and keeps the line it was asked. */
    private class Proxy1 : AutoCloseable {
        val socket = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        @Volatile var asked: String? = null
        private val worker = thread(isDaemon = true) {
            runCatching {
                socket.accept().use { client ->
                    asked = client.getInputStream().bufferedReader().readLine()
                    client.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray())
                }
            }
        }

        override fun close() = socket.close()
    }

    @Test
    fun requests_go_through_the_proxy_java_picks() = runBlocking {
        val before = ProxySelector.getDefault()
        Proxy1().use { proxy ->
            // What useSystemProxies hands Java on a Windows computer whose one proxy serves every protocol (the owner's
            // company PC): that proxy as HTTP for web addresses, and as SOCKS for the raw sockets Java opens itself
            // ("socket://"). A socket that asked would greet this HTTP proxy in SOCKS: "Malformed reply from SOCKS server".
            ProxySelector.setDefault(object : ProxySelector() {
                override fun select(uri: URI): List<Proxy> {
                    val type = if (uri.scheme == "socket") Proxy.Type.SOCKS else Proxy.Type.HTTP
                    return listOf(Proxy(type, InetSocketAddress(proxy.socket.inetAddress, proxy.socket.localPort)))
                }
                override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) = Unit
            })
            try {
                val body = HttpClient(DesktopNetwork.engine()).use { it.get("http://kadans.invalid/health/live").bodyAsText() }

                assertEquals("ok", body)
                assertEquals("GET http://kadans.invalid/health/live HTTP/1.1", proxy.asked)
            } finally {
                ProxySelector.setDefault(before)
            }
        }
    }

    @Test
    fun the_system_proxy_setting_is_asked_for() {
        DesktopNetwork.useSystemProxies()
        assertEquals("true", System.getProperty("java.net.useSystemProxies"))
    }

    private class Fake(private val trusts: Boolean, private val issuer: X509Certificate? = null) : X509TrustManager {
        var asked = 0
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            asked++
            if (!trusts) throw CertificateException("not mine")
        }
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = listOfNotNull(issuer).toTypedArray()
    }

    @Test
    fun a_server_is_trusted_when_java_or_the_computer_trusts_it() {
        val java = Fake(trusts = false)
        val windows = Fake(trusts = true)
        EitherTrustManager(listOf(java, windows)).checkServerTrusted(emptyArray(), "RSA")
        assertEquals(1, windows.asked)

        val javaTrusts = Fake(trusts = true)
        val untouched = Fake(trusts = true)
        EitherTrustManager(listOf(javaTrusts, untouched)).checkServerTrusted(emptyArray(), "RSA")
        assertEquals(0, untouched.asked, "Java's own answer is enough")
    }

    @Test
    fun a_server_nobody_trusts_is_refused_with_javas_reason() {
        val refusal = assertFailsWith<CertificateException> {
            EitherTrustManager(listOf(Fake(trusts = false), Fake(trusts = false))).checkServerTrusted(emptyArray(), "RSA")
        }
        assertEquals("not mine", refusal.message)
    }

    @Test
    fun javas_own_authorities_are_always_there() {
        assertTrue(DesktopNetwork.trust.acceptedIssuers.isNotEmpty())
    }
}

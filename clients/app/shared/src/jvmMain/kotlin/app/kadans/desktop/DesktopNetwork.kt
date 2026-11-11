package app.kadans.desktop

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import javax.net.SocketFactory
import okhttp3.OkHttpClient

/**
 * How the desktop app reaches the server from any network, a company's too. Browsers and most programs follow the
 * computer's proxy setting and trust the certificate authorities the computer trusts; Java does neither by itself.
 * Behind a company proxy that blocks direct connections, every request failed while the browser worked (the owner's
 * first Windows test, 2026-11-11: no Google button, no sign-in).
 */
object DesktopNetwork {
    /**
     * Connections go through the proxy the computer is set to (Windows: Internet Options, the same as Edge and Chrome;
     * a PAC script too). First thing in `main`: Java reads the setting once, when it first picks a proxy.
     */
    fun useSystemProxies() {
        System.setProperty("java.net.useSystemProxies", "true")
    }

    /** The engine of every desktop HTTP client: the proxy Java picks (above), and the authorities of [trust]. */
    fun engine(): HttpClientEngine = OkHttp.create {
        config { desktop(trust, socketFactory) }
    }

    /** What every desktop OkHttp client needs (the engine above, `--network-check`): [DirectSockets], [tls] with [trust]. */
    internal fun OkHttpClient.Builder.desktop(trust: X509TrustManager, tls: SSLSocketFactory): OkHttpClient.Builder =
        socketFactory(DirectSockets).sslSocketFactory(tls, trust)

    /**
     * Java's own certificate authorities and, on Windows, the computer's as well. A company proxy that inspects secure
     * connections signs them with its own authority, which IT adds to Windows (where browsers find it), never to Java.
     * Hostnames are still checked, by OkHttp, whichever authority vouched for the certificate.
     */
    val trust: X509TrustManager by lazy { EitherTrustManager(listOfNotNull(trustManagerOf(null), windowsAuthorities())) }

    private val socketFactory: SSLSocketFactory by lazy {
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }.socketFactory
    }

    private val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows")

    /** The Windows store of trusted roots (SunMSCAPI, `jdk.crypto.mscapi` in the runtime image); none elsewhere. */
    private fun windowsAuthorities(): X509TrustManager? {
        if (!isWindows) return null
        return runCatching { trustManagerOf(windowsRoots()) }.getOrNull()
    }

    private fun windowsRoots(): KeyStore = KeyStore.getInstance("Windows-ROOT").apply { load(null, null) }

    /** For `--network-check` (NetworkCheck): what reading Windows' authorities gives. */
    internal fun windowsAuthoritiesReport(): String = when {
        !isWindows -> "none (not Windows)"
        else -> runCatching { "${windowsRoots().size()} certificates" }.getOrElse { "could not be read: ${it.javaClass.name}: ${it.message}" }
    }

    /** [store] null: Java's own authorities. */
    private fun trustManagerOf(store: KeyStore?): X509TrustManager =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(store) }
            .trustManagers.filterIsInstance<X509TrustManager>().first()
}

/**
 * Plain sockets that never pick a proxy themselves. OkHttp picks it (the system's, above) and speaks to it in HTTP
 * (`CONNECT`). A plain Java socket asks the proxy selector too, for "socket://", and on Windows the one proxy a computer
 * sets for every protocol comes back there as SOCKS: the socket then greeted an HTTP proxy in SOCKS and the connection
 * died ("Malformed reply from SOCKS server", the owner's company PC, 2026-11-11). Sockets for a SOCKS proxy OkHttp picks
 * itself it makes with that proxy, not through here.
 */
internal object DirectSockets : SocketFactory() {
    override fun createSocket(): Socket = Socket(Proxy.NO_PROXY)

    override fun createSocket(host: String, port: Int): Socket = createSocket().apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        createSocket().apply {
            bind(InetSocketAddress(localHost, localPort))
            connect(InetSocketAddress(host, port))
        }

    override fun createSocket(host: InetAddress, port: Int): Socket = createSocket().apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        createSocket().apply {
            bind(InetSocketAddress(localAddress, localPort))
            connect(InetSocketAddress(address, port))
        }
}

/** Trusts a server when any of [managers] does (the first is Java's own); otherwise refuses with the first one's reason. */
internal class EitherTrustManager(private val managers: List<X509TrustManager>) : X509TrustManager {
    init {
        require(managers.isNotEmpty())
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        var refusal: CertificateException? = null
        for (manager in managers) {
            try {
                manager.checkServerTrusted(chain, authType)
                return
            } catch (e: CertificateException) {
                if (refusal == null) refusal = e
            }
        }
        throw refusal!!
    }

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
        managers.first().checkClientTrusted(chain, authType)

    override fun getAcceptedIssuers(): Array<X509Certificate> =
        managers.flatMap { it.acceptedIssuers.asList() }.distinct().toTypedArray()
}

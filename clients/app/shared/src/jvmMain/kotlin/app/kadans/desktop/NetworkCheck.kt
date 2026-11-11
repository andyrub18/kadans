package app.kadans.desktop

import app.kadans.config.BuildConfig
import app.kadans.config.ServerAddress
import app.kadans.desktop.DesktopNetwork.desktop
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request

/**
 * `Kadans --network-check`: what the desktop app sees of the network, written to `kadans-network-check.txt` in the
 * person's home folder, for when it cannot reach the server (a company proxy, a certificate it does not trust). No
 * account data: the server's address, the proxy Java picks, the certificate authorities found, and what each step of
 * one request to the server's health check answered, with the certificates the connection presented.
 */
object NetworkCheck {
    const val FLAG = "--network-check"

    fun run(): Path {
        val report = mutableListOf<String>()
        val target = ServerAddress.builtInDefault().trimEnd('/') + "/health/live"

        report += "Kadans ${BuildConfig.APP_VERSION}, ${Instant.now()}"
        report += "System: ${prop("os.name")} ${prop("os.version")} (${prop("os.arch")}), Java ${prop("java.version")} (${prop("java.vendor")})"
        report += "java.net.useSystemProxies = ${prop("java.net.useSystemProxies")}"
        for (key in listOf("http.proxyHost", "https.proxyHost", "http.nonProxyHosts")) System.getProperty(key)?.let { report += "$key = $it" }
        report += "Proxy Java picks for $target: " +
            runCatching { ProxySelector.getDefault().select(URI(target)).toString() }.getOrElse { describe(it) }
        report += "Java's own certificate authorities: " + runCatching { javaAuthorities().size.toString() }.getOrElse { describe(it) }
        report += "Windows' certificate authorities: " + DesktopNetwork.windowsAuthoritiesReport()
        report += "Trusted by the app: " + runCatching { DesktopNetwork.trust.acceptedIssuers.size.toString() }.getOrElse { describe(it) }
        report += ""
        report += "GET $target"

        val steps = mutableListOf<String>()
        val recorder = RecordingTrustManager(DesktopNetwork.trust)
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(recorder), null) }
        val client = OkHttpClient.Builder()
            .desktop(recorder, tls.socketFactory)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(40, TimeUnit.SECONDS)
            .eventListener(Steps(steps))
            .build()
        val outcome = runCatching {
            client.newCall(Request.Builder().url(target).build()).execute().use { "${it.code} ${it.body.string().take(60)}" }
        }
        report += steps
        recorder.chain?.forEachIndexed { i, cert ->
            report += "  certificate $i: ${cert.subjectX500Principal.name}, issued by ${cert.issuerX500Principal.name}"
        }
        report += outcome.fold({ "Answer: $it" }, { "Failed: ${describe(it)}" })
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()

        val file = Path.of(prop("user.home"), "kadans-network-check.txt")
        Files.writeString(file, report.joinToString(System.lineSeparator(), postfix = System.lineSeparator()))
        return file
    }

    private fun prop(key: String): String = System.getProperty(key) ?: "(not set)"

    private fun javaAuthorities(): Array<X509Certificate> =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(null as java.security.KeyStore?) }
            .trustManagers.filterIsInstance<X509TrustManager>().first().acceptedIssuers

    /** Every cause in the chain: the last one usually says what really happened. */
    internal fun describe(e: Throwable): String =
        generateSequence(e) { it.cause }.take(6).joinToString(" <- ") { "${it.javaClass.name}: ${it.message}" }

    private class Steps(private val steps: MutableList<String>) : EventListener() {
        override fun proxySelectEnd(call: Call, url: HttpUrl, proxies: List<Proxy>) {
            steps += "  proxies to try: $proxies"
        }

        override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
            steps += "  connecting to $inetSocketAddress ($proxy)"
        }

        override fun connectFailed(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?, ioe: java.io.IOException) {
            steps += "  that connection failed: ${describe(ioe)}"
        }

        override fun secureConnectEnd(call: Call, handshake: Handshake?) {
            steps += "  secure connection: ${handshake?.tlsVersion} ${handshake?.cipherSuite}"
        }
    }
}

/** Keeps the certificates the server (or a proxy in between) presented, then lets [inner] judge them. */
private class RecordingTrustManager(private val inner: X509TrustManager) : X509TrustManager {
    @Volatile
    var chain: List<X509Certificate>? = null

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        this.chain = chain.toList()
        inner.checkServerTrusted(chain, authType)
    }

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = inner.checkClientTrusted(chain, authType)

    override fun getAcceptedIssuers(): Array<X509Certificate> = inner.acceptedIssuers
}

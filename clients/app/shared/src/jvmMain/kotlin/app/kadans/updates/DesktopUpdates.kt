package app.kadans.updates

import app.kadans.desktop.openInBrowser
import app.kadans.i18n.LanguageController
import app.kadans.notifications.FreedesktopNotifications
import app.kadans.notifications.notificationIcon
import app.kadans.notifications.showSystemNotification
import app.kadans.reminders.DesktopReminderNotices
import app.kadans.ui.currentAppStrings
import com.russhwolf.settings.Settings
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

/**
 * Asks the site what the latest desktop release is. A client of its own, never the API's: it carries no account, no
 * token and nothing about the person, only the request every browser makes. It follows the site's redirect to GitHub.
 */
internal class DesktopUpdateCheck(private val client: HttpClient = defaultClient()) {
    /** The site's answer, or null: unreachable, not found, or too big to be the file. */
    suspend fun fetch(): String? = try {
        val response = client.get(AppUpdates.LATEST_URL)
        val tooBig = (response.contentLength() ?: 0) > AppUpdates.MAX_BODY
        if (response.status == HttpStatusCode.OK && !tooBig) response.bodyAsText().takeIf { it.length <= AppUpdates.MAX_BODY } else null
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private companion object {
        fun defaultClient() = HttpClient(OkHttp) {
            expectSuccess = false
            install(HttpTimeout) { requestTimeoutMillis = 30_000 }
        }
    }
}

/** When the installed app first asks (once the window and the reminders are up), then how often: it lives for days. */
private val FIRST_CHECK = 1.minutes
private val CHECK_EVERY = 24.hours
private const val ANNOUNCED = "kadans.updates.announced"
private const val DOWNLOAD = "download"

/**
 * The installed desktop app's update check (ARCHITECTURE → "The desktop app: in the tray, once"), started by its main.
 * A dev run (`./gradlew :desktopApp:run`) never asks: it is built from the newest code there is. A version is announced
 * once, by a notification whose click (or its "Download" button on Linux) opens the download page; Settings → This
 * computer shows it for as long as it is out.
 */
fun startUpdateChecks() {
    if (System.getProperty("jpackage.app-path").isNullOrBlank()) return
    val koin = GlobalContext.get()
    val updates = koin.get<AppUpdates>()
    val settings = koin.get<Settings>()
    val check = DesktopUpdateCheck()
    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
        delay(FIRST_CHECK)
        while (isActive) {
            val language = koin.get<LanguageController>().language.value.tag
            val notice = updates.read(check.fetch(), language)
            AppUpdates.toAnnounce(notice, settings.getStringOrNull(ANNOUNCED))?.let {
                announce(it)
                settings.putString(ANNOUNCED, it.version)
            }
            delay(CHECK_EVERY)
        }
    }
}

private fun announce(notice: UpdateNotice) {
    val s = currentAppStrings().updates
    val title = if (notice.required) s.requiredTitle else s.available(notice.version)
    val body = notice.notes ?: if (notice.required) s.requiredText else s.availableText
    val open = { runCatching { openInBrowser(AppUpdates.DOWNLOAD_PAGE) } }
    val linux = DesktopReminderNotices.session?.takeIf { it.clickable }
    val shown = linux?.show(title, body, notificationIcon, listOf(DOWNLOAD to s.download)) { key ->
        if (key == DOWNLOAD || key == FreedesktopNotifications.DEFAULT) open()
    }
    if (shown == null) showSystemNotification(title, body)
}

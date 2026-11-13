package app.kadans.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.kadans.api.model.CalendarFeedResponse
import app.kadans.api.model.DevicePlatform
import app.kadans.config.devicePlatform
import app.kadans.i18n.LocalStrings
import app.kadans.ui.LocalDates
import kotlinx.datetime.TimeZone
import org.koin.compose.viewmodel.koinViewModel

/**
 * Google Calendar's page that takes a calendar's address ("Other calendars" → "From URL"), on its website: the only
 * place Google lets a calendar be added from a link.
 */
internal const val GOOGLE_CALENDAR_ADD_BY_URL = "https://calendar.google.com/calendar/r/settings/addbyurl"

/** Google Calendar's phone apps cannot add a calendar from a link, and its website on a phone does not offer it. */
internal fun addsToGoogleHere(platform: DevicePlatform): Boolean = platform != DevicePlatform.Android && platform != DevicePlatform.Ios

/**
 * Settings → My todos in my calendar (ARCHITECTURE → "The calendar feed"). Off: what it does and what a calendar gets.
 * On: the link, to copy, and where each calendar app takes it; how late a calendar may show a change; that the link
 * is a secret; replacing it and turning it off, each asked twice. Without the phones' subscription, which the feed is
 * part of: where to subscribe, and nothing to turn on.
 *
 * The link is https and is only ever copied, never handed over as `webcal://`: a calendar app given that reads it over
 * plain http, which would send the secret in the clear at every read.
 */
@Composable
internal fun CalendarFeedPage(
    viewModel: CalendarFeedViewModel = koinViewModel(),
    onComputer: Boolean = remember { addsToGoogleHere(devicePlatform()) },
) {
    val state by viewModel.state.collectAsState()
    val s = LocalStrings.current
    val c = s.calendarFeed

    if (state.error != null || state.errorCode != null) {
        Text(s.errorFor(state.errorCode, state.error), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
    val feed = state.feed
    val url = feed?.url
    when {
        feed == null && state.isLoading ->
            Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        feed == null -> OutlinedButton(onClick = { viewModel.load() }, modifier = Modifier.fillMaxWidth()) { Text(s.retry) }
        // The feed is part of the phones' subscription. A phone without one is behind the paywall: this is the free
        // desktop app, which sells nothing, so it says where to subscribe. A link already given out can still go.
        !feed.available -> {
            Text(c.intro, style = MaterialTheme.typography.bodyMedium)
            Text(c.needsSubscription, style = MaterialTheme.typography.bodyMedium)
            if (feed.enabled) {
                Text(c.paused, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TurnOff(enabled = !state.isBusy, onConfirmed = viewModel::turnOff)
            }
        }
        !feed.enabled || url == null -> {
            Text(c.intro, style = MaterialTheme.typography.bodyMedium)
            Text(c.whatItShows, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = viewModel::turnOn, enabled = !state.isBusy, modifier = Modifier.fillMaxWidth()) { Text(c.turnOn) }
        }
        else -> FeedOn(feed, url, state.isBusy, onComputer, viewModel)
    }
}

@Composable
private fun FeedOn(feed: CalendarFeedResponse, url: String, busy: Boolean, onComputer: Boolean, viewModel: CalendarFeedViewModel) {
    val s = LocalStrings.current
    val c = s.calendarFeed
    val dates = LocalDates.current
    val uriHandler = LocalUriHandler.current
    @Suppress("DEPRECATION") // LocalClipboard's ClipEntry is built differently on each platform; plain text is all this needs
    val clipboard = LocalClipboardManager.current
    // Both go back to their first wording when the link changes.
    var copied by remember(url) { mutableStateOf(false) }
    var googleOpened by remember(url) { mutableStateOf(false) }
    var replacing by remember { mutableStateOf(false) }

    Text(c.whatItShows, style = MaterialTheme.typography.bodyMedium)
    Text(
        feed.lastFetchedAt?.let { c.lastRead(dates.moment(it, TimeZone.currentSystemDefault())) } ?: c.neverRead,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(c.linkLabel, style = MaterialTheme.typography.labelMedium)
            SelectionContainer { Text(url, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(url))
                copied = true
            }) { Text(if (copied) c.linkCopied else c.copyLink) }
        }
    }
    Text(c.secret, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

    HorizontalDivider(Modifier.padding(vertical = 8.dp))

    Text(c.googleTitle, style = MaterialTheme.typography.titleSmall)
    Text(if (onComputer) c.googleOnComputer else c.googleOnPhone, style = MaterialTheme.typography.bodySmall)
    if (onComputer) {
        OutlinedButton(
            onClick = {
                clipboard.setText(AnnotatedString(url))
                googleOpened = runCatching { uriHandler.openUri(GOOGLE_CALENDAR_ADD_BY_URL) }.isSuccess
                copied = true
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(c.openGoogle) }
        if (googleOpened) Text(c.googleOpened, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
    }
    Text(c.othersTitle, style = MaterialTheme.typography.titleSmall)
    Text(c.othersSteps, style = MaterialTheme.typography.bodySmall)
    Text(c.delay, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

    HorizontalDivider(Modifier.padding(vertical = 8.dp))

    OutlinedButton(onClick = { replacing = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(c.replace) }
    TurnOff(enabled = !busy, onConfirmed = viewModel::turnOff)

    if (replacing) {
        AlertDialog(
            onDismissRequest = { replacing = false },
            title = { Text(c.replaceTitle) },
            text = { Text(c.replaceText) },
            confirmButton = {
                TextButton(onClick = {
                    replacing = false
                    viewModel.replaceLink()
                }) { Text(c.replace) }
            },
            dismissButton = { TextButton(onClick = { replacing = false }) { Text(s.cancel) } },
        )
    }
}

/** "Turn off", asked twice: the link stops for good, and the calendars that follow it with it. */
@Composable
private fun TurnOff(enabled: Boolean, onConfirmed: () -> Unit) {
    val s = LocalStrings.current
    val c = s.calendarFeed
    var asking by remember { mutableStateOf(false) }

    TextButton(onClick = { asking = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Text(c.turnOff, color = MaterialTheme.colorScheme.error)
    }
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text(c.turnOffTitle) },
            text = { Text(c.turnOffText) },
            confirmButton = {
                TextButton(onClick = {
                    asking = false
                    onConfirmed()
                }) { Text(c.turnOff, color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { asking = false }) { Text(s.cancel) } },
        )
    }
}

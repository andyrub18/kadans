package app.kadans.ui.shell

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kadans.i18n.LocalStrings
import app.kadans.i18n.StringsCatalog
import org.koin.compose.koinInject

/** The four destinations, one tap away from each other. Home is where the app opens and where Back leads. */
enum class MainTab { Home, Calendar, Budget, Focus }

/** What + does on a destination that adds something: Home a todo, Budget a movement. */
class AddAction(val label: String, val onClick: () -> Unit)

/**
 * The destinations' frame: their name, the bell (with the unread count) and Settings on top; the destinations in a
 * bar at the bottom of a phone, or in a column on the left of a wide window (a computer, a tablet), + at its top.
 * Everything else (a todo, a form, Settings) opens over it with its own way back.
 */
@Composable
fun MainShell(
    tab: MainTab,
    title: String,
    onTab: (MainTab) -> Unit,
    onNotifications: () -> Unit,
    onSettings: () -> Unit,
    add: AddAction? = null,
    unread: UnreadNotifications = koinInject(),
    content: @Composable (Modifier) -> Unit,
) {
    val s = LocalStrings.current
    val count by unread.count.collectAsState()
    // Each time a destination comes on screen (back from the list, which marks them read): the server's count.
    LaunchedEffect(Unit) { unread.refresh() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= WIDE) {
            Row(Modifier.fillMaxSize()) {
                NavigationRail(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer, // set apart from the page, as the phone's bar
                    header = {
                        add?.let {
                            FloatingActionButton(onClick = it.onClick, modifier = Modifier.padding(vertical = 8.dp)) {
                                Icon(KadansIcons.Add, contentDescription = it.label)
                            }
                        }
                    },
                ) {
                    MainTab.entries.forEach { t ->
                        NavigationRailItem(
                            selected = t == tab,
                            onClick = { if (t != tab) onTab(t) },
                            icon = { Icon(t.icon, contentDescription = null) },
                            label = { Text(t.label(s)) },
                        )
                    }
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    TopBar(title, count, onNotifications, onSettings)
                    content(Modifier.weight(1f).fillMaxWidth())
                }
            }
        } else {
            Scaffold(
                topBar = { TopBar(title, count, onNotifications, onSettings) },
                bottomBar = {
                    NavigationBar {
                        MainTab.entries.forEach { t ->
                            NavigationBarItem(
                                selected = t == tab,
                                onClick = { if (t != tab) onTab(t) },
                                icon = { Icon(t.icon, contentDescription = null) },
                                label = { Text(t.label(s), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            )
                        }
                    }
                },
                floatingActionButton = {
                    add?.let { FloatingActionButton(onClick = it.onClick) { Icon(KadansIcons.Add, contentDescription = it.label) } }
                },
            ) { padding -> content(Modifier.padding(padding).fillMaxSize()) }
        }
    }
}

@Composable
private fun TopBar(title: String, unread: Int, onNotifications: () -> Unit, onSettings: () -> Unit) {
    val s = LocalStrings.current
    Row(
        Modifier.fillMaxWidth().height(64.dp).padding(start = 20.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onNotifications) {
            BadgedBox(badge = { if (unread > 0) Badge { Text(UnreadNotifications.badgeText(unread)) } }) {
                Icon(KadansIcons.Bell, contentDescription = if (unread > 0) s.nav.unread(unread) else s.notifications.title)
            }
        }
        IconButton(onClick = onSettings) { Icon(KadansIcons.Settings, contentDescription = s.settings) }
    }
}

/** A window this wide shows the destinations in a column on its left (Material's medium width and up). */
private val WIDE = 600.dp

private val MainTab.icon: ImageVector
    get() = when (this) {
        MainTab.Home -> KadansIcons.Home
        MainTab.Calendar -> KadansIcons.Calendar
        MainTab.Budget -> KadansIcons.Budget
        MainTab.Focus -> KadansIcons.Focus
    }

private fun MainTab.label(s: StringsCatalog): String = when (this) {
    MainTab.Home -> s.nav.home
    MainTab.Calendar -> s.calendar
    MainTab.Budget -> s.budget
    MainTab.Focus -> s.nav.focus
}

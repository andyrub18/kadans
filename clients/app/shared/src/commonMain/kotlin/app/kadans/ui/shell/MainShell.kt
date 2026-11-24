package app.kadans.ui.shell

import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kadans.i18n.LocalStrings
import app.kadans.i18n.StringsCatalog
import app.kadans.ui.BigButton
import app.kadans.ui.CircleButton
import app.kadans.ui.softShadow
import org.koin.compose.koinInject

/** The four destinations, one tap away from each other. Home is where the app opens and where Back leads. */
enum class MainTab { Home, Calendar, Budget, Focus }

/** What + does on a destination that adds something: Home a todo, Budget a movement. */
class AddAction(val label: String, val onClick: () -> Unit)

/**
 * The destinations' frame (ARCHITECTURE → "The look"). On a phone: the destination's name on top with the bell (and
 * its unread count) and Settings, the four destinations in a bar at the bottom, + floating over the list. In a wide
 * window (a computer, a tablet): the destinations in a column on the left with the bell and Settings at its foot, and
 * what + does as a button with its name, top right.
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
    subtitle: String? = null,
    unread: UnreadNotifications = koinInject(),
    content: @Composable (Modifier) -> Unit,
) {
    val count by unread.count.collectAsState()
    // Each time a destination comes on screen (back from the list, which marks them read): the server's count.
    LaunchedEffect(Unit) { unread.refresh() }

    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (maxWidth >= WIDE) {
            Row(Modifier.fillMaxSize()) {
                SideBar(tab, onTab, count, onNotifications, onSettings)
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 8.dp, end = 28.dp, top = 24.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        Heading(title, subtitle, MaterialTheme.typography.displaySmall, Modifier.weight(1f))
                        add?.let { BigButton(it.label, it.onClick, icon = KadansIcons.Add) }
                    }
                    content(Modifier.weight(1f).fillMaxWidth())
                }
            }
        } else {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                topBar = { TopBar(title, subtitle, count, onNotifications, onSettings) },
                bottomBar = { BottomBar(tab, onTab) },
                floatingActionButton = { add?.let { AddButton(it) } },
            ) { padding -> content(Modifier.padding(padding).fillMaxSize()) }
        }
    }
}

@Composable
private fun AddButton(add: AddAction) {
    FloatingActionButton(
        onClick = add.onClick,
        modifier = Modifier.size(60.dp).softShadow(CircleShape),
        shape = CircleShape,
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp),
    ) { Icon(KadansIcons.Add, contentDescription = add.label, modifier = Modifier.size(26.dp)) }
}

/** A destination's name, large, under a smaller line when it has one (Home: the day's date over "Today"). */
@Composable
private fun Heading(title: String, subtitle: String?, style: TextStyle, modifier: Modifier = Modifier) {
    Column(modifier) {
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(title, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun TopBar(title: String, subtitle: String?, unread: Int, onNotifications: () -> Unit, onSettings: () -> Unit) {
    val s = LocalStrings.current
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Heading(title, subtitle, MaterialTheme.typography.headlineLarge, Modifier.weight(1f))
        BadgedBox(badge = { if (unread > 0) Badge { Text(UnreadNotifications.badgeText(unread)) } }) {
            CircleButton(KadansIcons.Bell, if (unread > 0) s.nav.unread(unread) else s.notifications.title, onNotifications)
        }
        CircleButton(KadansIcons.Settings, s.settings, onSettings)
    }
}

/** The phone's bar: white, round at the top, the destination one is on under a lilac pill. */
@Composable
private fun BottomBar(tab: MainTab, onTab: (MainTab) -> Unit) {
    val s = LocalStrings.current
    val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    Surface(modifier = Modifier.softShadow(shape, 16.dp), shape = shape, color = MaterialTheme.colorScheme.surfaceContainer) {
        NavigationBar(containerColor = Color.Transparent, tonalElevation = 0.dp) {
            MainTab.entries.forEach { t ->
                NavigationBarItem(
                    selected = t == tab,
                    onClick = { if (t != tab) onTab(t) },
                    icon = { Icon(t.icon, contentDescription = null) },
                    // Four words share a phone's width: "Concentration" gets a little smaller before it reaches the edge.
                    label = {
                        Text(
                            t.label(s),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = 12.sp, stepSize = 0.5.sp),
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                )
            }
        }
    }
}

/** A wide window's column: the app's name, the destinations by name, and at its foot the bell and Settings. */
@Composable
private fun SideBar(tab: MainTab, onTab: (MainTab) -> Unit, unread: Int, onNotifications: () -> Unit, onSettings: () -> Unit) {
    val s = LocalStrings.current
    val shape = MaterialTheme.shapes.extraLarge
    Surface(
        modifier = Modifier.padding(16.dp).width(SIDE_BAR).fillMaxHeight().softShadow(shape),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "Kadans",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(start = 12.dp, bottom = 12.dp),
            )
            MainTab.entries.forEach { t ->
                SideBarItem(t.icon, t.label(s), selected = t == tab, onClick = { if (t != tab) onTab(t) })
            }
            Spacer(Modifier.weight(1f))
            SideBarItem(
                KadansIcons.Bell,
                s.notifications.title,
                selected = false,
                onClick = onNotifications,
                spoken = if (unread > 0) s.nav.unread(unread) else null,
                badge = if (unread > 0) UnreadNotifications.badgeText(unread) else null,
            )
            SideBarItem(KadansIcons.Settings, s.settings, selected = false, onClick = onSettings)
        }
    }
}

@Composable
private fun SideBarItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    spoken: String? = null,
    badge: String? = null,
) {
    val content = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(CircleShape)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .then(if (spoken != null) Modifier.semantics { contentDescription = spoken } else Modifier)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // The count sits on the bell, as on a phone: beside the word it left "Notifications" no room.
        BadgedBox(badge = { if (badge != null) Badge { Text(badge) } }) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(22.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // "Notificaciones" is the longest: it gets a little smaller before it is cut.
            autoSize = TextAutoSize.StepBased(minFontSize = 12.sp, maxFontSize = 15.sp, stepSize = 1.sp),
            modifier = Modifier.weight(1f),
        )
    }
}

/** A window this wide shows the destinations in a column on its left (Material's medium width and up). */
private val WIDE = 600.dp
private val SIDE_BAR = 216.dp

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

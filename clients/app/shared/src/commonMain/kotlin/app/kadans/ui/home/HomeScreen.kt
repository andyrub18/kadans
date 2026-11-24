package app.kadans.ui.home

import app.kadans.ui.BigButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kadans.api.model.TodoOccurrenceResponse
import app.kadans.api.model.TodoResponse
import app.kadans.calendar.PhoneCalendar
import app.kadans.i18n.LocalStrings
import app.kadans.ui.Dates
import app.kadans.ui.FabClearance
import app.kadans.ui.KadansCard
import app.kadans.ui.LocalDates
import app.kadans.ui.ProgressRing
import app.kadans.ui.ReadableList
import app.kadans.ui.SectionHeader
import app.kadans.ui.Tag
import app.kadans.ui.TickCircle
import app.kadans.ui.calendar.DayItem
import app.kadans.ui.calendar.PhoneEventCard
import app.kadans.ui.calendar.daysWithItems
import kotlin.time.Clock
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/**
 * Home, inside the destinations' frame (MainShell: the day's name, the bell, + for a new todo): how far today is, its
 * todos with a circle to mark each done, then the days that follow (ARCHITECTURE → "The look").
 */
@Composable
fun HomeScreen(
    onLoggedOut: () -> Unit,
    onOpenTodo: (String) -> Unit,
    onPaywall: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = koinViewModel(),
) {
    val access by viewModel.access.collectAsState()
    LaunchedEffect(access) { if (access == HomeAccess.Paywall) onPaywall() }
    if (access != HomeAccess.Open) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    val state by viewModel.state.collectAsState()
    val ticked by viewModel.ticked.collectAsState()
    val s = LocalStrings.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(viewModel) { viewModel.loggedOut.collect { onLoggedOut() } }
    LaunchedEffect(viewModel) {
        viewModel.liveNotifications.collect { notification ->
            snackbar.showSnackbar(notification.title + " — " + notification.body)
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.tickFailed.collect { failure -> snackbar.showSnackbar(s.errorFor(failure?.errorCode ?: "network", failure?.message)) }
    }
    // Reload whenever this entry comes (back) on screen; data may have changed behind us.
    LaunchedEffect(Unit) { viewModel.refresh() }

    // A circle pressed: done at once on the screen, and a few seconds to take it back before it is sent.
    val onTick: (String) -> Unit = { occurrenceId ->
        viewModel.tick(occurrenceId)
        scope.launch {
            snackbar.currentSnackbarData?.dismiss() // one undo at a time: the one before is past taking back
            val answer = snackbar.showSnackbar(s.pomodoro.markedDone, actionLabel = s.widget.undo, duration = SnackbarDuration.Short)
            if (answer == SnackbarResult.ActionPerformed) viewModel.untick(occurrenceId)
        }
    }

    Scaffold(modifier = modifier, containerColor = Color.Transparent, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        when (val current = state) {
            is HomeUiState.Loading ->
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            is HomeUiState.Error ->
                Column(
                    Modifier.fillMaxSize().padding(padding),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(s.errorFor(current.code, current.message), color = MaterialTheme.colorScheme.error)
                    BigButton(s.retry, viewModel::refresh, Modifier.padding(top = 12.dp))
                }
            is HomeUiState.Content -> HomeContent(current, ticked, onOpenTodo, onTick, Modifier.padding(padding))
        }
    }
}

@Composable
private fun HomeContent(
    content: HomeUiState.Content,
    ticked: Set<String>,
    onOpenTodo: (String) -> Unit,
    onTick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val zone = TimeZone.currentSystemDefault()
    val today = Dates.today(zone)
    // Under each day, "Tomorrow", then its name: the times on the device's clock, as the person lives them.
    // The phone's own events are among the todos where the person turned that on (a day may have only events).
    val days = daysWithItems(content.upcoming, content.events, zone, today, today.plus(7, DateTimeUnit.DAY))
    val progress = TodayProgress.of(content.upcoming, today, zone, Clock.System.now(), ticked)
    val focusTodos = remember(content.todos) { content.todos.filter { it.pomodoroTemplateId != null }.map { it.id }.toSet() }
    val day = HomeDay(
        today = today,
        items = days.firstOrNull { it.first == today }?.second.orEmpty(),
        later = days.filter { it.first != today },
        progress = progress,
        ticked = ticked,
        focusTodos = focusTodos,
    )

    BoxWithConstraints(modifier.fillMaxSize()) {
        if (maxWidth >= TwoColumns) {
            // A computer: today on the left, the days that follow beside it, each scrolling by itself.
            Row(Modifier.fillMaxSize().padding(start = 8.dp, end = 28.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                LazyColumn(Modifier.weight(3f), contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp), verticalArrangement = Rows) {
                    today(day, onOpenTodo, onTick, emptyWord = day.later.isEmpty())
                    allTodos(content.todos, onOpenTodo)
                }
                LazyColumn(Modifier.weight(2f), contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp), verticalArrangement = Rows) {
                    later(day, onOpenTodo, onTick, first = true)
                }
            }
        } else {
            ReadableList(Modifier.fillMaxSize(), bottom = FabClearance) { padding ->
                LazyColumn(Modifier.fillMaxSize(), contentPadding = padding, verticalArrangement = Rows) {
                    today(day, onOpenTodo, onTick, emptyWord = day.later.isEmpty())
                    later(day, onOpenTodo, onTick, first = false)
                    allTodos(content.todos, onOpenTodo)
                }
            }
        }
    }
}

/** What Home shows of the week: today's list and its progress, then the days that follow. */
private class HomeDay(
    val today: LocalDate,
    val items: List<DayItem>,
    val later: List<Pair<LocalDate, List<DayItem>>>,
    val progress: TodayProgress,
    val ticked: Set<String>,
    val focusTodos: Set<String>,
)

private val Rows = Arrangement.spacedBy(10.dp)

/** A list this wide puts the days that follow beside today, not under it. */
private val TwoColumns = 720.dp

private fun LazyListScope.today(day: HomeDay, onOpenTodo: (String) -> Unit, onTick: (String) -> Unit, emptyWord: Boolean) {
    if (day.progress.total > 0) item(key = "progress") { DayCard(day.progress) }
    if (day.items.isEmpty() && emptyWord) {
        item(key = "nothing") { Text(LocalStrings.current.nothingScheduled, style = MaterialTheme.typography.bodyMedium) }
    }
    dayItems(day.today, day.items, day, onOpenTodo, onTick)
}

private fun LazyListScope.later(day: HomeDay, onOpenTodo: (String) -> Unit, onTick: (String) -> Unit, first: Boolean) {
    day.later.forEachIndexed { index, (date, items) ->
        item(key = "day $date") {
            Text(
                LocalDates.current.day(date, day.today),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = if (first && index == 0) 0.dp else 8.dp),
            )
        }
        dayItems(date, items, day, onOpenTodo, onTick)
    }
}

private fun LazyListScope.dayItems(date: LocalDate, items: List<DayItem>, day: HomeDay, onOpenTodo: (String) -> Unit, onTick: (String) -> Unit) {
    items(
        items,
        key = {
            when (it) {
                is DayItem.Todo -> it.occurrence.id ?: (it.occurrence.todoId + it.occurrence.scheduledAt.toString())
                is DayItem.Event -> "event $date ${it.event.id} ${it.event.start}"
            }
        },
    ) { item ->
        when (item) {
            is DayItem.Todo -> {
                val occurrence = item.occurrence
                OccurrenceRow(
                    occurrence = occurrence,
                    time = LocalDates.current.time(occurrence.scheduledAt, TimeZone.currentSystemDefault()),
                    done = TodayProgress.isDone(occurrence, day.ticked),
                    next = occurrence.id != null && occurrence.id == day.progress.next?.id,
                    focus = occurrence.todoId in day.focusTodos,
                    onClick = { onOpenTodo(occurrence.todoId) },
                    onTick = occurrence.id?.let { id -> { onTick(id) } },
                )
            }
            is DayItem.Event -> {
                val phoneCalendar = koinInject<PhoneCalendar>()
                PhoneEventCard(item.event, date, onClick = { phoneCalendar.open(item.event) })
            }
        }
    }
}

private fun LazyListScope.allTodos(todos: List<TodoResponse>, onOpenTodo: (String) -> Unit) {
    item(key = "all todos") { SectionHeader(LocalStrings.current.allTodos, Modifier.padding(top = 8.dp)) }
    if (todos.isEmpty()) {
        item(key = "no todos") { Text(LocalStrings.current.noTodosYet, style = MaterialTheme.typography.bodyMedium) }
    }
    items(todos, key = { "todo ${it.id}" }) { todo -> TodoCard(todo, onClick = { onOpenTodo(todo.id) }) }
}

/** How far the day is: a ring with the count in it, the same in words, and the todo that comes next with its time. */
@Composable
private fun DayCard(progress: TodayProgress) {
    val h = LocalStrings.current.home
    val sentence = h.doneOf(progress.done, progress.total)
    KadansCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ProgressRing(progress.fraction, Modifier.size(92.dp).semantics { contentDescription = sentence }) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${progress.done}", style = MaterialTheme.typography.headlineSmall)
                    Text(h.of(progress.total), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (progress.allDone) h.allDone else sentence, style = MaterialTheme.typography.titleLarge)
                progress.next?.let { next ->
                    Text(
                        h.next(next.todoTitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Tag(LocalDates.current.time(next.scheduledAt, TimeZone.currentSystemDefault()))
                }
            }
        }
    }
}

/**
 * One time of a todo: its circle (pressed: done, see [Ticks]), its title and hour, and "Focus" when the todo has a
 * cycle. The next one to do today stands out; a done one is struck through and stays where it was.
 */
@Composable
private fun OccurrenceRow(
    occurrence: TodoOccurrenceResponse,
    time: String,
    done: Boolean,
    next: Boolean,
    focus: Boolean,
    onClick: () -> Unit,
    onTick: (() -> Unit)?,
) {
    val s = LocalStrings.current
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (next) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
    ) {
        Row(
            Modifier.padding(start = 4.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TickCircle(
                done = done,
                description = if (done) s.occCompleted else s.widget.markDone(occurrence.todoTitle),
                emphasized = next,
                onClick = onTick,
            )
            Column(Modifier.weight(1f)) {
                Text(
                    occurrence.todoTitle,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    textDecoration = if (done) TextDecoration.LineThrough else null,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (next) "$time · ${s.home.next}" else time,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (next) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (focus) {
                Tag(s.focus, container = if (next) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.primaryContainer)
            }
        }
    }
}

@Composable
private fun TodoCard(todo: TodoResponse, onClick: () -> Unit) {
    val s = LocalStrings.current
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                Text(todo.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (todo.description.isNotBlank()) {
                    Text(
                        todo.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Tag(s.statusName(todo.status))
        }
    }
}

package app.kadans.widget

import app.kadans.api.KadansApi
import app.kadans.api.KadansApiException
import app.kadans.api.KadansJson
import app.kadans.api.model.ClockFormat
import app.kadans.i18n.StringsCatalog
import app.kadans.billing.SubscriptionGate
import app.kadans.realtime.RealtimeEvent
import app.kadans.sync.DataArea
import app.kadans.sync.DataChanges
import com.russhwolf.settings.Settings
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * The home screen's side of the widget: whether one is placed, and drawing them all. [TodayWidget] decides what they
 * show; this only talks to the system. Android has widgets; the desktop and (for now) the iPhone have none
 * ([NoWidgetSurface]).
 */
interface WidgetSurface {
    /** A widget is on a home screen: without one, nothing is fetched for it. */
    fun present(): Boolean

    /**
     * Draws every placed widget: the pending todos [entries] (each widget shows today's, as many as it holds), or the
     * sentence for [gap] when the day cannot be shown. [doneAt]: the occurrences whose "Done" was tapped, each with the
     * moment its completion is sent (`TodayWidgetContent.of`). Done when it returns.
     */
    suspend fun show(entries: List<WidgetEntry>?, gap: WidgetGap?, doneAt: Map<String, Instant>)

    /** The home screen takes a widget when an app asks (Android 8+, most launchers): Settings then offers it. */
    fun canAdd(): Boolean

    /** Asks the home screen to add the widget; it shows its own confirmation. From a screen of the app only. */
    fun add()
}

object NoWidgetSurface : WidgetSurface {
    override fun present() = false

    override suspend fun show(entries: List<WidgetEntry>?, gap: WidgetGap?, doneAt: Map<String, Instant>) = Unit

    override fun canAdd() = false

    override fun add() = Unit
}

/**
 * This platform's home-screen widgets (Android: `TodayWidgetProvider`). [catalog]: the words in the app's language;
 * [clock]: the account's, 12 or 24 hours.
 */
expect fun platformWidgetSurface(catalog: () -> StringsCatalog, clock: () -> ClockFormat): WidgetSurface

/**
 * What the widget holds between fetches, in the app's settings: today's and tomorrow's pending todos as last fetched
 * (shown offline, and after midnight until the next fetch), and why there may be none to show.
 */
class TodayWidgetStore(private val settings: Settings) {
    /** Null: never fetched for this session. */
    fun entries(): List<WidgetEntry>? =
        settings.getStringOrNull(ENTRIES)?.let { runCatching { KadansJson.decodeFromString(serializer, it) }.getOrNull() }

    fun save(entries: List<WidgetEntry>) {
        saveEntries(entries)
        locked = false
    }

    /** The same list with a line less: its "Done" was just sent, before the next fetch says so. */
    fun saveEntries(entries: List<WidgetEntry>) = settings.putString(ENTRIES, KadansJson.encodeToString(serializer, entries))

    /** The account has no subscription for phones: the widget says so instead of the day. */
    var locked: Boolean
        get() = settings.getBoolean(LOCKED, false)
        set(value) = settings.putBoolean(LOCKED, value)

    /**
     * The occurrences whose "Done" was tapped on the widget and not sent yet, each with the moment it is sent: kept
     * here, so a process Android stopped in between loses none.
     */
    fun doneAt(): Map<String, Instant> =
        settings.getStringOrNull(DONE)
            ?.let { runCatching { KadansJson.decodeFromString(doneSerializer, it) }.getOrNull() }
            .orEmpty()
            .mapValues { Instant.fromEpochMilliseconds(it.value) }

    fun saveDoneAt(doneAt: Map<String, Instant>) =
        if (doneAt.isEmpty()) settings.remove(DONE)
        else settings.putString(DONE, KadansJson.encodeToString(doneSerializer, doneAt.mapValues { it.value.toEpochMilliseconds() }))

    /** Signed out: nothing of the account stays on the device. */
    fun clear() {
        settings.remove(ENTRIES)
        settings.remove(LOCKED)
        settings.remove(DONE)
    }

    private companion object {
        const val ENTRIES = "kadans.widget.entries"
        const val LOCKED = "kadans.widget.locked"
        const val DONE = "kadans.widget.done"
        val serializer = ListSerializer(WidgetEntry.serializer())
        val doneSerializer = MapSerializer(String.serializer(), Long.serializer())
    }
}

/**
 * The home-screen widget's data (ARCHITECTURE → "The home-screen widget"): today's and tomorrow's pending todos, fetched
 * whenever they may have changed and kept for when the phone is offline. The platform draws them ([WidgetSurface]) and
 * asks for a fetch itself when the system wakes it (placed, every hour, a new day, a push saying "changed").
 *
 * Nothing is fetched while no widget is placed, nor behind the paywall: a phone without a subscription shows the
 * widget locked, as the app is.
 */
class TodayWidget(
    private val api: KadansApi,
    private val store: TodayWidgetStore,
    private val surface: WidgetSurface,
    private val gate: SubscriptionGate,
    realtimeEvents: Flow<RealtimeEvent>,
    private val dataChanges: DataChanges,
    /** An occurrence was marked done from the widget: its reminders stay quiet on this device (`LocalReminders.settled`). */
    private val settled: suspend (occurrenceId: String) -> Unit = {},
    private val now: () -> Instant = { Clock.System.now() },
    private val zone: () -> TimeZone = { TimeZone.currentSystemDefault() },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    /** One fetch at a time. */
    private val fetching = Mutex()

    /** The taps on "Done" and "Undo" and the sending, one at a time: what is waiting changes in one place. */
    private val marking = Mutex()

    /** Moved by [forget]: what a fetch begun before a sign-out brings back is dropped. Read from any thread. */
    @Volatile
    private var session = 0

    /** When the last fetch that succeeded began: coming back to the front skips a fresh one. Read from any thread. */
    @Volatile
    private var fetchedAt: Instant? = null

    private val requests = Channel<Unit>(Channel.CONFLATED)

    init {
        // Requests made while a fetch runs collapse into one more fetch after it.
        scope.launch { for (request in requests) attempt { refresh() } }
        // The todos changed: on this device (its own saves do not wait for the hub), on another one, by a notification's
        // button here, or the hub is back after a drop that may have lost the signal.
        scope.launch {
            val heard = realtimeEvents.filter { it is RealtimeEvent.RemindersChanged || it is RealtimeEvent.Reconnected }.map { }
            merge(heard, api.remindersChanged, dataChanges.of(DataArea.Todos)).collect { refreshSoon() }
        }
        // The account's language, time zone or clock changed here: the same todos read differently.
        scope.launch { api.profileChanged.collect { draw() } }
    }

    /** Something changed: fetched again, soon. */
    fun refreshSoon() {
        requests.trySend(Unit)
    }

    /** The app came to the front: fetched again unless a fetch began in the last few minutes. */
    fun refreshIfStale() {
        val last = fetchedAt
        if (last != null && now() - last < FRESH) return
        refreshSoon()
    }

    /**
     * The system asked for the widget (placed, the phone restarted, the app updated): fetched, unless a fetch began in
     * the last few minutes, and drawn either way. Done when it returns. The system's call can also be made by any app
     * on the phone (a widget's provider is exported): made over and over, it costs one fetch every few minutes.
     */
    suspend fun refreshUnlessFresh() {
        val last = fetchedAt
        if (last != null && now() - last < FRESH) draw() else refresh()
    }

    /**
     * Drawn again from what is held, without asking the server: the app left the front (its language, clock or
     * appearance may have been changed in it), a new day began, a widget was resized.
     */
    fun redrawSoon() {
        scope.launch { draw() }
    }

    /**
     * Fetches today's and tomorrow's pending todos and draws the widgets. Offline, or the server away: what is shown
     * stays, the next signal tries again. Throws nothing but cancellation.
     */
    suspend fun refresh() = fetching.withLock {
        if (!surface.present()) return@withLock
        val started = session
        if (api.tokenStore.load() == null) {
            draw()
            return@withLock
        }
        // A "Done" whose five seconds have passed goes first: what is fetched then already knows it.
        sendDue(announce = false)
        val began = now()
        val fetched = attempt {
            if (gate.appliesHere && SubscriptionGate.blocks(api.billing.status())) null
            else {
                val zone = zone()
                val today = began.toLocalDateTime(zone).date
                api.todos
                    .occurrencesBetween(today.atStartOfDayIn(zone), today.plus(2, DateTimeUnit.DAY).atStartOfDayIn(zone))
                    .map { WidgetEntry(it.todoId, it.todoTitle, it.scheduledAt, it.id) }
            }
        }
        if (session != started) return@withLock // signed out meanwhile: it belongs to no one here
        when (fetched) {
            is Fetched.Failed -> fetchedAt = null
            is Fetched.Value -> {
                if (fetched.value == null) store.locked = true else store.save(fetched.value)
                fetchedAt = began
            }
        }
        draw()
    }

    /** As [redrawSoon], done when it returns: for the platform's own callers, which wait for it. */
    suspend fun redraw() = draw()

    /** Whether Settings offers "Add to my home screen" on this device. */
    val canBeAdded: Boolean get() = surface.canAdd()

    /** Settings' button: the home screen asks the person to confirm, and the system then has the widget drawn. */
    fun addToHomeScreen() = surface.add()

    /** Signed out: nothing of the account stays, and the widget says to sign in. */
    suspend fun forget() {
        session++
        fetchedAt = null
        store.clear()
        draw()
    }

    /** What is waiting to be sent, for the platform to wake at the right moment (Android: an alarm, a job). */
    data class DoneQueue(
        /** The soonest moment a tapped "Done" becomes due; null when none is still inside its five seconds. */
        val nextDue: Instant?,
        /** Some are due and could not be sent (offline, the server away): they go at the next chance. */
        val waitsForNetwork: Boolean,
    )

    /**
     * A line's circle was tapped: the line shows struck through with "Undo" for [UNDO_FOR], and only then is its
     * completion sent ([sendDue]). A completion cannot be undone on the server, and a tap on a home screen is easy to
     * make by accident: the five seconds are the undo. Tapped twice, the first tap's moment stays.
     */
    suspend fun markDone(occurrenceId: String): DoneQueue = marking.withLock {
        val waiting = store.doneAt()
        if (occurrenceId !in waiting) store.saveDoneAt(waiting + (occurrenceId to now() + UNDO_FOR))
        draw()
        queue()
    }

    /** "Undo", inside the five seconds: nothing was sent, the todo is still to do. Later than that, it changes nothing. */
    suspend fun undo(occurrenceId: String): DoneQueue = marking.withLock {
        val waiting = store.doneAt()
        val due = waiting[occurrenceId]
        if (due != null && due > now()) store.saveDoneAt(waiting - occurrenceId)
        draw()
        queue()
    }

    /**
     * Sends the completions whose five seconds have passed. Sent, or already done or skipped elsewhere: the line is
     * gone and the device's reminders for it stay quiet. The server unreachable: it stays waiting, its line already
     * gone, and goes at the next chance. Refused for good (the todo was deleted, the session ended): dropped, and the
     * next fetch shows what is true. [announce]: tell the app's screens and fetch the widget again once something went.
     */
    suspend fun sendDue(announce: Boolean = true): DoneQueue = marking.withLock {
        val started = session
        var sentSome = false
        val current = now()
        val due = store.doneAt().filterValues { it <= current }.keys
        if (due.isEmpty()) return@withLock queue()
        for (occurrenceId in due) {
            val outcome = complete(occurrenceId)
            if (session != started) return@withLock DoneQueue(null, false) // signed out meanwhile
            if (outcome == Sent.Later) continue
            store.saveDoneAt(store.doneAt() - occurrenceId)
            if (outcome == Sent.Done) {
                store.entries()?.let { held -> store.saveEntries(held.filterNot { it.occurrenceId == occurrenceId }) }
                runCatching { settled(occurrenceId) }
                sentSome = true
            }
        }
        draw()
        if (sentSome && announce) dataChanges.changed(DataArea.Todos)
        queue()
    }

    private fun queue(): DoneQueue {
        val waiting = store.doneAt()
        val current = now()
        return DoneQueue(waiting.values.filter { it > current }.minOrNull(), waiting.values.any { it <= current })
    }

    private enum class Sent { Done, Later, Refused }

    private suspend fun complete(occurrenceId: String): Sent =
        try {
            api.todos.completeOccurrence(occurrenceId)
            Sent.Done
        } catch (e: CancellationException) {
            throw e
        } catch (e: KadansApiException) {
            when {
                e.errorCode == ALREADY_DONE || e.errorCode == ALREADY_CANCELLED -> Sent.Done
                e.httpStatus >= 500 || e.httpStatus == 429 -> Sent.Later
                else -> Sent.Refused
            }
        } catch (_: Exception) {
            Sent.Later // offline, or the server restarting
        }

    private suspend fun draw() {
        if (!surface.present()) return
        val entries = store.entries()
        val gap = when {
            api.tokenStore.load() == null -> WidgetGap.SignedOut
            store.locked -> WidgetGap.Locked
            entries == null -> WidgetGap.NotLoaded
            else -> null
        }
        surface.show(entries, gap, store.doneAt())
    }

    private sealed interface Fetched<out T> {
        data class Value<T>(val value: T) : Fetched<T>

        data object Failed : Fetched<Nothing>
    }

    /** The work's value; a failure (offline, the server away, a session just ended) is "failed", never a crash. */
    private suspend fun <T> attempt(block: suspend () -> T): Fetched<T> =
        try {
            Fetched.Value(block())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Fetched.Failed
        }

    companion object {
        /** A fetch this recent is fresh enough for the app coming back to the front. */
        val FRESH = 2.minutes

        /** How long a line tapped "Done" waits, struck through with "Undo", before its completion is sent. */
        val UNDO_FOR = 5.seconds

        /** `ErrorTypes`: TaskAlreadyCompleted, TaskAlreadyCancelled. Done or skipped elsewhere meanwhile: settled all the same. */
        internal const val ALREADY_DONE = "10005"
        internal const val ALREADY_CANCELLED = "10020"
    }
}

package app.kadans.quiet

import app.kadans.api.KadansApi
import app.kadans.api.KadansJson
import app.kadans.api.model.ClockFormat
import app.kadans.api.model.PomodoroRunResponse
import app.kadans.api.model.PomodoroRunStatus
import app.kadans.i18n.StringsCatalog
import app.kadans.realtime.RealtimeEvent
import com.russhwolf.settings.Settings
import app.kadans.sync.listen
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/** Whether this device can go quiet during focus, and if not, what the person could turn on. */
enum class QuietAccess {
    Ready,

    /** Android's "Do Not Disturb access" is off for Kadans: it cannot switch a mode. */
    DoNotDisturbOff,

    /** Android's "Alarms & reminders" is off: the sound could come back late, so Kadans does not take it away. */
    ExactAlarmsOff,

    /** No such thing here: the desktop, the iPhone (Apple lets no app switch a Focus), Android before 10. */
    Unsupported,
}

/**
 * The operating system's side of "Do Not Disturb during focus" (ARCHITECTURE → "Do Not Disturb during focus").
 * [FocusQuiet] decides when the phone is quiet; this only talks to the system. Android: a mode of Kadans' own in the
 * system's Do Not Disturb settings (`AndroidQuietMode`), which never touches the person's own Do Not Disturb.
 */
interface QuietMode {
    fun access(): QuietAccess

    /** Opens the system's screen that turns on what [access] says is missing. From a screen of the app only. */
    fun openSettings(missing: QuietAccess)

    /**
     * Quiet from now on, for [span] (Kadans' mode on, and a silent notice saying until when, with "Sound on"); or, with
     * null, the sound back and the notice gone. Asked again for the same span, it shows the notice again, silently.
     */
    fun quiet(span: QuietSpan?)

    /**
     * Kadans' mode is off although Kadans turned it on: the person did, in the system's own settings (Android leaves
     * it off until the phase is over; Kadans, for the rest of the session). Asked only while Kadans holds the phone
     * quiet.
     */
    fun turnedOffByPerson(): Boolean

    /** [FocusQuiet.tick] is called at [at], the app closed or the phone asleep; null: no wake is needed. */
    fun wakeAt(at: Instant?)

    /** The switch was turned off: Kadans' mode leaves the system's settings. */
    fun remove()
}

/** A device that cannot go quiet during focus. */
object NoQuietMode : QuietMode {
    override fun access() = QuietAccess.Unsupported

    override fun openSettings(missing: QuietAccess) = Unit

    override fun quiet(span: QuietSpan?) = Unit

    override fun turnedOffByPerson() = false

    override fun wakeAt(at: Instant?) = Unit

    override fun remove() = Unit
}

/**
 * This platform's mode (Android 10 and later: `AndroidQuietMode`). [catalog]: the words in the app's language (the
 * mode's name in the system's settings, the notice); [clock]: the account's, for the notice's "until".
 */
expect fun platformQuietMode(catalog: () -> StringsCatalog, clock: () -> ClockFormat): QuietMode

/** What Settings shows for "Do Not Disturb during focus". */
enum class QuietState { Unsupported, Off, On, NeedsDoNotDisturbAccess, NeedsExactAlarms }

/**
 * A session as this phone last heard of it: its focus phases still ahead ([spans]), and how recent that news is.
 * [learnedAt] is this phone's clock, [updatedAt] the server's: each is only ever compared with its own kind.
 */
@Serializable
data class KnownRun(
    val updatedAt: Instant,
    val learnedAt: Instant,
    val spans: List<QuietSpan>,
    /** Finished or ended: it never runs again, and an older "it runs" arriving late is not believed. */
    val ended: Boolean = false,
    /** The person asked for the sound back during this session: it stays on until the session is over. */
    val soundOn: Boolean = false,
)

/**
 * What "Do Not Disturb during focus" keeps in the app's settings: the switch (this device's own, as its language is),
 * and, for the session signed in, the sessions' timetable and what the phone is quiet for now, so a process Android
 * stopped in between picks up where it was.
 */
class FocusQuietStore(private val settings: Settings) {
    /** The switch in Settings. */
    var wanted: Boolean
        get() = settings.getBoolean(WANTED, false)
        set(value) = settings.putBoolean(WANTED, value)

    /** The session screen's one-time offer was answered, or the switch used: it is not made again. */
    var offered: Boolean
        get() = settings.getBoolean(OFFERED, false)
        set(value) = settings.putBoolean(OFFERED, value)

    /** The account is behind the paywall on this phone: nothing is followed. */
    var locked: Boolean
        get() = settings.getBoolean(LOCKED, false)
        set(value) = settings.putBoolean(LOCKED, value)

    /** What the server was last told about this phone going quiet; null: nothing yet for this sign-in. */
    var reported: Boolean?
        get() = settings.getBooleanOrNull(REPORTED)
        set(value) = if (value == null) settings.remove(REPORTED) else settings.putBoolean(REPORTED, value)

    fun known(): Map<String, KnownRun> =
        settings.getStringOrNull(KNOWN)?.let { runCatching { KadansJson.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()

    fun saveKnown(known: Map<String, KnownRun>) =
        if (known.isEmpty()) settings.remove(KNOWN) else settings.putString(KNOWN, KadansJson.encodeToString(serializer, known))

    /** The phase the phone was last made quiet for; null: it has its sound. */
    var quietFor: QuietSpan?
        get() = settings.getStringOrNull(QUIET_FOR)?.let { runCatching { KadansJson.decodeFromString(QuietSpan.serializer(), it) }.getOrNull() }
        set(value) =
            if (value == null) settings.remove(QUIET_FOR)
            else settings.putString(QUIET_FOR, KadansJson.encodeToString(QuietSpan.serializer(), value))

    /** Signed out: nothing of the account stays. The switch does: it is the phone's, like the access Android gave. */
    fun clearSession() {
        settings.remove(KNOWN)
        settings.remove(LOCKED)
        settings.remove(REPORTED)
    }

    private companion object {
        const val WANTED = "kadans.quiet.wanted"
        const val OFFERED = "kadans.quiet.offered"
        const val LOCKED = "kadans.quiet.locked"
        const val REPORTED = "kadans.quiet.reported"
        const val KNOWN = "kadans.quiet.runs"
        const val QUIET_FOR = "kadans.quiet.for"
        val serializer = MapSerializer(String.serializer(), KnownRun.serializer())
    }
}

/**
 * "Do Not Disturb during focus" (ARCHITECTURE → "Do Not Disturb during focus"): with its switch on, the phone is quiet
 * while a focus phase of one of the account's sessions counts down, and has its sound back in a break, a pause, with
 * the timer at 0:00 and once the session is over.
 *
 * A session runs on the server, so the phone keeps its timetable ([QuietPlan]) and follows it with one alarm
 * ([QuietMode.wakeAt] → [tick]), the app closed or offline. It hears of a change from its own requests ([see], through
 * the API), from the hub while the app is open, and from a push when the session changed on another device
 * ([refresh]). Whatever it misses, the sound comes back at the planned end of the phase it went quiet for: early in the
 * worst case, never late.
 */
class FocusQuiet(
    private val api: KadansApi,
    private val store: FocusQuietStore,
    private val mode: QuietMode,
    realtimeEvents: Flow<RealtimeEvent>,
    /** Tells the server whether this phone goes quiet (its device's registration); true once it went through. */
    private val report: suspend () -> Boolean = { true },
    private val now: () -> Instant = { Clock.System.now() },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val checkWithin: Duration = CHECK_WITHIN,
) {
    /** One fetch at a time. */
    private val fetching = Mutex()

    /** What is known and what the phone is set to change in one place. No request is made under it. */
    private val settling = Mutex()

    /** Moved by [forget]: what a fetch begun before a sign-out brings back is dropped. Read from any thread. */
    @Volatile
    private var session = 0

    private val requests = Channel<Unit>(Channel.CONFLATED)

    private val _state = MutableStateFlow(stateNow())

    /** What Settings shows. */
    val state: StateFlow<QuietState> = _state.asStateFlow()

    private val _offer = MutableStateFlow(offerNow())

    /** The session screen offers the switch, once, on a phone that can and has it off. */
    val offer: StateFlow<Boolean> = _offer.asStateFlow()

    init {
        // Requests made while a fetch runs collapse into one more fetch after it.
        scope.launch { for (request in requests) attempt { refresh() } }
        // The app is open: a session changed anywhere arrives on the hub as it is, and a hub that comes back after a
        // drop may have lost one.
        scope.listen(realtimeEvents) { event ->
            when (event) {
                is RealtimeEvent.PomodoroRunChanged -> attempt { see(event.run) }
                is RealtimeEvent.Reconnected -> refreshSoon()
                else -> Unit
            }
        }
    }

    /**
     * The switch is on and Android lets Kadans do it: what the server is told (a session changed on another device is
     * then pushed to this phone). Null on a device that never could, which says nothing.
     */
    val reportable: Boolean?
        get() = when (mode.access()) {
            QuietAccess.Unsupported -> null
            QuietAccess.Ready -> store.wanted
            else -> false
        }

    /**
     * The switch, on. What is missing has its system screen opened (one at a time: the person comes back, and
     * [cameBack] takes it from there); with nothing missing, the sessions running now are followed at once. Returns
     * what was missing.
     */
    suspend fun turnOn(): QuietAccess {
        store.wanted = true
        store.offered = true
        val access = mode.access()
        publish()
        if (access == QuietAccess.Ready) refresh() else mode.openSettings(access)
        return access
    }

    /** The switch, off: the sound back at once, and Kadans' mode gone from the system's settings. */
    suspend fun turnOff() {
        store.wanted = false
        store.offered = true
        settling.withLock {
            store.saveKnown(emptyMap())
            settleLocked()
        }
        mode.remove()
        reportIfChanged()
    }

    /** The session screen's offer was declined: it is not made again (Settings keeps the switch). */
    fun offerDeclined() {
        store.offered = true
        publish()
    }

    /** Opens the system's screen for what is still missing. */
    fun openSettings() = mode.openSettings(mode.access())

    /**
     * The app came to the front, maybe from the system's settings: what Android allows is read again, and with the
     * switch on the sessions are fetched (the access just given starts it; one taken away is told to the server).
     */
    fun cameBack() {
        publish()
        if (store.wanted) refreshSoon()
    }

    /** Something may have changed: fetched again, soon. */
    fun refreshSoon() {
        requests.trySend(Unit)
    }

    /**
     * Home opened, past the paywall or stopped by it ([closed]): a phone without a subscription follows nothing, as
     * the server pushes it nothing.
     */
    suspend fun paywall(closed: Boolean) {
        store.locked = closed
        if (!closed) return refreshSoon()
        settling.withLock {
            store.saveKnown(emptyMap())
            settleLocked()
        }
    }

    /**
     * A session as the server just described it: this device's own request (start, pause, resume, next, finish), or the
     * hub's word. Taken as it is unless what is held is newer, and the phone settles at once: no second request, so a
     * network that drops right after "Pause" still gives the sound back.
     */
    suspend fun see(run: PomodoroRunResponse) {
        if (!follows()) return
        settling.withLock {
            val current = now()
            store.saveKnown(kept(taken(store.known(), run, current), current))
            settleLocked()
        }
    }

    /**
     * Reads the account's running sessions and settles: the truth, whatever was missed. Offline, or the server away:
     * the timetable held is followed. True when the sessions were read.
     *
     * [show]: a push brought the news; Firebase delivers one at once only when it shows something, so a phone that is
     * quiet shows its notice again. [force]: after a restart, see [restore].
     */
    suspend fun refresh(show: Boolean = false, force: Boolean = false): Boolean = fetching.withLock {
        val started = session
        if (!follows()) {
            settle(force)
            reportIfChanged()
            return@withLock false
        }
        val began = now()
        val runs = attempt { api.pomodoro.activeRuns() }
        if (session != started) return@withLock false // signed out meanwhile: it belongs to no one here
        if (runs == null) {
            settle(force, show)
            return@withLock false
        }
        settling.withLock {
            val current = now()
            var known = store.known()
            for (run in runs) known = taken(known, run, current)
            val listed = runs.mapTo(mutableSetOf()) { it.id }
            // A session the server no longer lists is over, unless this phone heard of it after asking (a start
            // answered while the list was on its way).
            known = known.mapValues { (id, held) ->
                if (!held.ended && id !in listed && held.learnedAt <= began) held.copy(ended = true, spans = emptyList()) else held
            }
            store.saveKnown(kept(known, current))
            settleLocked(force, show)
        }
        reportIfChanged()
        true
    }

    /**
     * The alarm: a phase began or ended by the timetable. The sound coming back never waits. Going quiet asks the
     * server first, while it answers ([checkWithin]): the session may have been paused or ended elsewhere and the news
     * not have arrived. Offline, the timetable is followed.
     */
    suspend fun tick() {
        val goingQuiet = settling.withLock {
            follows() && store.quietFor == null && QuietPlan.current(spans(store.known()), now()) != null
        }
        if (!goingQuiet || withTimeoutOrNull(checkWithin) { refresh() } == null) settle()
    }

    /** The notice's "Sound on": the sound is back, and stays for the rest of the sessions running now. */
    suspend fun soundOn() = settling.withLock {
        store.saveKnown(withSoundOn(store.known()))
        settleLocked()
    }

    /**
     * Android says Kadans' mode went off. It says so whoever did it, Kadans included, so the announcement decides
     * nothing: the phone settles now rather than at its next alarm, and settling looks whether the person turned the
     * mode off while Kadans holds the phone quiet (as "Sound on") or Kadans did, which it has recorded already.
     */
    suspend fun modeWentOff() = settle()

    /**
     * The phone restarted, the app was updated, or Android's access changed: the system's side is set again from what
     * is held (a restart keeps no alarm, and may not keep the mode on), then the sessions are read.
     */
    suspend fun restore() {
        settle(force = true)
        refresh(force = true)
    }

    /**
     * The person removed Kadans' mode in the system's settings, or disabled it there: that is the switch, off. Not when
     * Android took the mode away with its access: the switch stays, and Settings says what is missing.
     */
    suspend fun modeRemoved() {
        if (store.wanted && mode.access() == QuietAccess.Ready) turnOff()
    }

    /** Signed out: the sound back, and nothing of the account stays. */
    suspend fun forget() {
        session++
        settling.withLock {
            store.clearSession()
            settleLocked()
        }
    }

    private suspend fun settle(force: Boolean = false, show: Boolean = false) = settling.withLock { settleLocked(force, show) }

    /**
     * Makes the phone what the timetable says for this moment and sets the alarm for the next change. Under
     * [settling]. It first looks whether the person turned Kadans' mode off themselves while it was on: their word
     * holds for the rest of the sessions running. [force] skips that look and sets the system's side again whatever is
     * held (a restart is not the person's doing); [show] sets it again while quiet (the notice, shown anew).
     */
    private suspend fun settleLocked(force: Boolean = false, show: Boolean = false) {
        val follows = follows()
        var known = if (follows) store.known() else emptyMap()
        val was = store.quietFor
        if (was != null && follows && !force && mode.turnedOffByPerson()) {
            known = withSoundOn(known)
            store.saveKnown(known)
        }
        val current = now()
        val spans = spans(known)
        val span = QuietPlan.current(spans, current)
        if (span != was || force || (show && span != null)) {
            mode.quiet(span)
            store.quietFor = span
        }
        mode.wakeAt(if (follows) QuietPlan.nextChange(spans, current) else null)
        publish()
    }

    /** Whether sessions are followed at all: the switch on, Android allowing it, signed in, past the paywall. */
    private suspend fun follows(): Boolean =
        store.wanted && !store.locked && mode.access() == QuietAccess.Ready && api.tokenStore.load() != null

    /** The server learns whether this phone goes quiet when that changed, and again until it went through. */
    private suspend fun reportIfChanged() {
        val quiet = reportable ?: return
        if (store.reported == quiet || api.tokenStore.load() == null) return
        if (attempt { report() } == true) store.reported = quiet
    }

    private fun publish() {
        _state.value = stateNow()
        _offer.value = offerNow()
    }

    private fun stateNow(): QuietState {
        val access = mode.access()
        return when {
            access == QuietAccess.Unsupported -> QuietState.Unsupported
            !store.wanted -> QuietState.Off
            access == QuietAccess.DoNotDisturbOff -> QuietState.NeedsDoNotDisturbAccess
            access == QuietAccess.ExactAlarmsOff -> QuietState.NeedsExactAlarms
            else -> QuietState.On
        }
    }

    private fun offerNow(): Boolean = mode.access() != QuietAccess.Unsupported && !store.wanted && !store.offered

    /** [block]'s value, or null when it failed (offline, the server away, a session just ended); cancellation cancels. */
    private suspend fun <T> attempt(block: suspend () -> T): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    internal companion object {
        /** The push that says a session changed on another device (the server's `FocusSignal.Kind`). */
        const val CHANGED_KIND = "focus.changed"

        /** How long the alarm waits for the server before going quiet by the timetable alone. */
        val CHECK_WITHIN = 2.seconds

        /** How long a session that ended is remembered, so that late news of it is not believed. */
        val REMEMBERED = 24.hours

        /** Sessions remembered at most: an account runs a handful at a time. */
        const val KEPT = 30

        /** [run] as the news of it, unless what is held is newer or the session is known to be over. */
        fun taken(known: Map<String, KnownRun>, run: PomodoroRunResponse, now: Instant): Map<String, KnownRun> {
            val held = known[run.id]
            if (held != null && (held.ended || held.updatedAt > run.updatedAt)) return known
            val ended = run.status == PomodoroRunStatus.Completed || run.status == PomodoroRunStatus.Cancelled
            val spans = if (ended) emptyList() else QuietPlan.of(run, now)
            return known + (run.id to KnownRun(run.updatedAt, now, spans, ended, soundOn = held?.soundOn == true))
        }

        /** The focus phases the phone goes quiet for: those of the sessions running, but where the sound was asked back. */
        fun spans(known: Map<String, KnownRun>): List<QuietSpan> =
            known.values.filter { !it.ended && !it.soundOn }.flatMap { it.spans }

        fun withSoundOn(known: Map<String, KnownRun>): Map<String, KnownRun> =
            known.mapValues { (_, held) -> if (held.ended) held else held.copy(soundOn = true) }

        /** What is worth keeping: the sessions running, and those over for less than [REMEMBERED]; the newest [KEPT]. */
        fun kept(known: Map<String, KnownRun>, now: Instant): Map<String, KnownRun> =
            known.filterValues { !it.ended || now - it.learnedAt < REMEMBERED }
                .entries.sortedByDescending { it.value.learnedAt }.take(KEPT)
                .associate { it.key to it.value }
    }
}

package app.kadans.reminders

import app.kadans.api.KadansApi
import app.kadans.api.KadansApiException
import app.kadans.api.model.ReminderCheckResponse
import app.kadans.api.model.ReminderWindowResponse
import app.kadans.api.model.UpcomingReminder
import app.kadans.push.DeviceRegistrar
import app.kadans.realtime.RealtimeEvent
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The reminders this device rings itself (ARCHITECTURE → "Reminders ring on the phone"). The server writes them and
 * hands over the next days ([sync]); the OS rings them at their minute, connected or not ([ringDue]). A push for one
 * that already rang is dropped ([pushed]), so each shows once. Where the device cannot ring them (no permission, a
 * platform without a scheduler), the server is told to push them all, as before.
 *
 * What rings is always read from the stored window: a reminder the last sync dropped (deleted, done, moved) never
 * rings, and after a reboot the same window is scheduled again ([restore]).
 */
class LocalReminders(
    private val api: KadansApi,
    private val registrar: DeviceRegistrar,
    private val scheduler: ReminderScheduler,
    private val store: ReminderStore,
    realtimeEvents: Flow<RealtimeEvent>,
    private val now: () -> Instant = { Clock.System.now() },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val checkWithin: Duration = CHECK_WITHIN,
) {
    /** One window fetch at a time. */
    private val fetching = Mutex()

    /** One ring or push at a time: each reminder shows once. */
    private val ringing = Mutex()

    /** The stored window and what the OS rings change together. Never held across a call to the server. */
    private val state = Mutex()

    /** Moved by [forget]: what a fetch or a check begun before it brings back is dropped. */
    private var session = 0

    /** When the last fetch of this session began (cleared when it failed): the foreground skips a fresh window. */
    private var fetchedAt: Instant? = null

    private val requests = Channel<Unit>(Channel.CONFLATED)

    init {
        // Requests made while a fetch runs collapse into one more fetch after it. A failed fetch never stops the next.
        scope.launch { for (request in requests) attempt { sync() } }
        // The account's reminders changed (here or elsewhere), the hub is back after a drop that may have lost that
        // signal, or this device changed the account's language or time zone, which the reminders' words follow. From
        // the start, so a change made as the app opens is not missed.
        scope.launch {
            val signals = realtimeEvents.filter { it is RealtimeEvent.RemindersChanged || it is RealtimeEvent.Reconnected }
            merge(signals, api.profileChanged).collect { syncSoon() }
        }
    }

    /** Something changed: the window is fetched again, soon. */
    fun syncSoon() {
        requests.trySend(Unit)
    }

    /**
     * The app came to the foreground, or Home opened (signed in, past the paywall): the window is fetched again unless
     * a fetch began in the last few minutes.
     */
    fun refresh() {
        val last = fetchedAt
        if (last != null && now() - last < FRESH) return
        syncSoon()
    }

    /**
     * Fetches the window and rings it from now on. Where this device cannot ring reminders, makes sure the server
     * pushes them instead, and rings nothing. Offline, what is scheduled stays as it is.
     */
    suspend fun sync() = fetching.withLock {
        if (api.tokenStore.load() == null) return@withLock
        val installationId = registrar.installationId() ?: return@withLock
        val started = state.withLock { session }

        if (scheduler.access() != ReminderAccess.Ready) {
            fetchedAt = null
            if (store.syncing && attempt { api.reminders.stop(installationId) } != null) store.syncing = false
            state.withLock {
                if (session != started) return@withLock
                store.saveWindow(emptyList())
                scheduler.schedule(emptyList())
                // Until the server has heard it, the twice-daily job keeps telling it.
                scheduler.keepFresh(store.syncing)
            }
            return@withLock
        }

        val window = fetch(installationId) ?: return@withLock
        state.withLock {
            if (session != started) return@withLock // signed out meanwhile: the window belongs to no one here
            store.saveWindow(window.reminders)
            store.syncing = true
            reschedule()
            // A window that reaches no further than now: the account has no access on phones (the paywall) for now.
            scheduler.keepFresh(window.through > window.syncedAt)
        }
    }

    /**
     * The alarm rang: shows every reminder now due that has not rung here. When the server answers within two
     * seconds, one no longer due at that time (deleted, done or moved on another device since the window was
     * fetched) stays quiet; offline, it rings: the phone cannot know better. Then the next alarm is set.
     */
    suspend fun ringDue() = ringing.withLock {
        val (started, due) = state.withLock { session to due(now()) }
        val answers = if (due.isEmpty()) emptyList() else checks(due)
        state.withLock {
            if (session != started) return@withLock
            var moved = false
            due.forEachIndexed { i, reminder ->
                val answer = answers[i]
                when {
                    answer == null || (answer.due && answer.notifyAt == reminder.notifyAt) -> scheduler.show(reminder)
                    answer.due -> moved = true // its new time comes with the next window
                }
            }
            store.markRung(due, now())
            reschedule()
            if (moved) syncSoon()
        }
    }

    /** A push brought it (this device's window was stale, or never had it): shown unless it already rang here. */
    suspend fun pushed(reminder: UpcomingReminder) = ringing.withLock {
        if (api.tokenStore.load() == null) return@withLock // it outlived the session it was sent to
        state.withLock {
            if (store.rang(reminder.occurrenceId, reminder.notifyAt)) return@withLock
            scheduler.show(reminder)
            store.markRung(listOf(reminder), now())
        }
    }

    /**
     * After a reboot, an app update or the permission granted, the OS rings nothing: the stored window is scheduled
     * again (one that came due meanwhile rings at once, if its event has not started), then fetched afresh.
     */
    suspend fun restore() {
        if (api.tokenStore.load() == null) return
        state.withLock { if (scheduler.access() == ReminderAccess.Ready) reschedule() }
        sync()
    }

    /** Signed out, or the paywall closed the app: nothing rings here any more, and nothing of the account stays. */
    suspend fun forget() {
        // Whatever cancels the caller (a sign-out leaving its screen), the alarms go.
        withContext(NonCancellable) {
            state.withLock {
                session++
                fetchedAt = null
                scheduler.schedule(emptyList())
                scheduler.keepFresh(false)
                store.clear()
            }
        }
    }

    /** This occurrence's reminder, at this time, already rang here: the hub's copy only updates the bell. */
    fun rangHere(occurrenceId: String, notifyAt: Instant): Boolean = store.rang(occurrenceId, notifyAt)

    fun access(): ReminderAccess = scheduler.access()

    /** The first time a todo with a reminder is saved here, the person is asked once, with an explanation. */
    fun shouldOfferPermission(): Boolean = scheduler.access() == ReminderAccess.ExactAlarmsOff && !store.permissionAsked

    /** They answered (either way): not asked again unprompted; Settings still offers it. */
    fun permissionAnswered() {
        store.permissionAsked = true
    }

    /** The system's screen for what is missing; the window follows when the app comes back. */
    fun openSettings() = scheduler.openSettings(scheduler.access())

    /** Due now and not rung here, oldest first. */
    private fun due(at: Instant) = ringing(at).filter { it.notifyAt <= at }

    /** What still has to ring, from the stored window: not rung here, and not too late to be of use. */
    private fun ringing(at: Instant) = store.unrung(store.window()).filter { at < lastUseful(it) }.sortedBy { it.notifyAt }

    /** Under [state]. */
    private fun reschedule() = scheduler.schedule(ringing(now()))

    private suspend fun fetch(installationId: String): ReminderWindowResponse? {
        fetchedAt = now()
        val window = try {
            api.reminders.sync(installationId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: KadansApiException) {
            // Not registered yet (a first sign-in racing the registration): register, then once more.
            if (e.httpStatus == 404) {
                registrar.register()
                attempt { api.reminders.sync(installationId) }
            } else null
        } catch (_: Exception) {
            null // offline: what is scheduled still rings
        }
        if (window == null) fetchedAt = null
        return window
    }

    /** Each reminder's answer, asked together; null for one the server did not answer in time. */
    private suspend fun checks(due: List<UpcomingReminder>): List<ReminderCheckResponse?> = coroutineScope {
        due.map { reminder ->
            async { withTimeoutOrNull(checkWithin) { attempt { api.reminders.check(reminder.occurrenceId) } } }
        }.awaitAll()
    }

    /** [block]'s value, or null when it failed; cancellation still cancels. */
    private inline fun <T> attempt(block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    internal companion object {
        /** A reminder, as the server sends it (push, hub, notification centre). */
        const val DUE_KIND = "occurrence.due"

        /** The account's reminders changed: a device that rings them fetches its window again. */
        const val CHANGED_KIND = "reminders.changed"

        /** How long a ringing alarm waits for the server's word before it rings anyway. */
        val CHECK_WITHIN = 2.seconds

        /** A reminder rung late (a reboot, a phone that was off) is of use until its event starts, or this long after its time. */
        val LATE = 10.minutes

        /** The foreground fetches the window again only when the last fetch is older than this. */
        val FRESH = 5.minutes

        fun lastUseful(reminder: UpcomingReminder): Instant = maxOf(reminder.startsAt, reminder.notifyAt + LATE)
    }
}

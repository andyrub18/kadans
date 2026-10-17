package app.kadans.ui

import app.kadans.api.KadansApi
import app.kadans.api.model.ApiDayOfWeek
import app.kadans.api.model.Frequency
import app.kadans.api.model.OccurrenceStatus
import app.kadans.api.model.RecurrenceRuleResponse
import app.kadans.api.model.TodoOccurrenceResponse
import app.kadans.ui.todos.CreateTodoUiState
import app.kadans.ui.todos.CreateTodoViewModel
import app.kadans.ui.todos.DayKind
import app.kadans.ui.todos.DayRule
import app.kadans.ui.todos.EditTodoViewModel
import app.kadans.ui.todos.EndMode
import app.kadans.ui.todos.Ordinal
import app.kadans.ui.todos.RuleDraft
import app.kadans.ui.todos.TodoMode
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Editing a recurring todo: the stored rule read back into the form, sent only when changed, from a moment ahead. */
class EditTodoRuleTests {
    private val portAuPrince = TimeZone.of("America/Port-au-Prince")
    private val monday = LocalDate(2027, 1, 4)

    /** As the server answers, the RRULE as Ical.Net writes it; started Monday 4 January 2027 at 07:00 in Port-au-Prince. */
    private fun stored(rrule: String, frequency: Frequency, interval: Int = 1, count: Int? = null, remaining: Int? = null, until: String? = null) =
        RecurrenceRuleResponse(
            rrule = rrule, timeZoneId = portAuPrince.id, startDate = Instant.parse("2027-01-04T12:00:00Z"),
            frequency = frequency, interval = interval, count = count, until = until?.let(Instant::parse), remaining = remaining,
        )

    private fun draft(rule: RecurrenceRuleResponse, firstOn: LocalDate = LocalDate(2027, 2, 1)) = RuleDraft.of(rule, firstOn)

    /** What the form would send for [state] at a moment before it starts. */
    private fun sent(state: CreateTodoUiState) =
        CreateTodoViewModel.buildRecurring(state, portAuPrince, Instant.parse("2027-01-01T00:00:00Z")).recurrenceRule

    @Test
    fun a_weekly_rule_comes_back_with_its_days_its_time_and_what_is_left() {
        val alarm = draft(stored("FREQ=WEEKLY;COUNT=20;BYDAY=MO,TU,WE,TH,FR", Frequency.Weekly, count = 20, remaining = 12))

        assertTrue(alarm.worded)
        with(alarm.state) {
            assertEquals(TodoMode.Recurring, mode)
            assertEquals(Frequency.Weekly, frequency)
            assertEquals(mondayFirstDays.take(5).toSet(), weekDays)
            assertEquals(LocalTime(7, 0), time)
            assertEquals(EndMode.AfterCount, endMode)
            assertEquals(12, count) // the 8 that came are not started again
            assertEquals(LocalDate(2027, 2, 1), date)
        }
    }

    @Test
    fun monthly_and_yearly_rules_come_back_as_the_form_makes_them() {
        with(draft(stored("FREQ=MONTHLY;BYDAY=MO,TU,WE,TH,FR;BYSETPOS=-1", Frequency.Monthly)).state) {
            assertEquals(DayRule.ByWeekday, dayRule)
            assertEquals(Ordinal.Last, effectiveOrdinal)
            assertEquals(DayKind.Weekday, effectiveKind)
        }
        with(draft(stored("FREQ=MONTHLY;BYMONTHDAY=1,15", Frequency.Monthly)).state) {
            assertEquals(DayRule.ByDate, dayRule)
            assertEquals(setOf(1, 15), effectiveMonthDays)
        }
        with(draft(stored("FREQ=YEARLY;BYDAY=SU;BYMONTH=5;BYSETPOS=2", Frequency.Yearly)).state) {
            assertEquals(Frequency.Yearly, frequency)
            assertEquals(setOf(5), effectiveMonths)
            assertEquals(Ordinal.Second, effectiveOrdinal)
            assertEquals(DayKind.Sunday, effectiveKind)
        }
        // How the form sends "the first Monday" of several months: a yearly rule again in the form.
        with(draft(stored("FREQ=MONTHLY;BYDAY=MO;BYMONTH=1,6;BYSETPOS=1", Frequency.Monthly)).state) {
            assertEquals(Frequency.Yearly, frequency)
            assertEquals(setOf(1, 6), effectiveMonths)
        }
        with(draft(stored("FREQ=DAILY;BYHOUR=8,14,20;BYMINUTE=0", Frequency.Daily)).state) {
            assertEquals(listOf(LocalTime(8, 0), LocalTime(14, 0), LocalTime(20, 0)), times)
        }
        // "The 15th and the 30th", the 30th moving to February's last day: the days picked again.
        with(draft(stored("FREQ=MONTHLY;BYMONTHDAY=15,28,29,30;BYSETPOS=1,-1", Frequency.Monthly)).state) {
            assertEquals(DayRule.ByDate, dayRule)
            assertEquals(setOf(15, 30), effectiveMonthDays)
        }
    }

    @Test
    fun a_rule_left_alone_would_send_back_the_rule_it_came_from() {
        listOf(
            stored("FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR", Frequency.Weekly),
            stored("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE,FR", Frequency.Weekly, interval = 2),
            stored("FREQ=MONTHLY;BYDAY=MO,TU,WE,TH,FR;BYSETPOS=-1", Frequency.Monthly),
            stored("FREQ=MONTHLY;BYMONTHDAY=1,15", Frequency.Monthly),
            stored("FREQ=YEARLY;BYMONTHDAY=1;BYMONTH=1,4,7,10", Frequency.Yearly),
            stored("FREQ=YEARLY;BYDAY=SU;BYMONTH=5;BYSETPOS=2", Frequency.Yearly),
            stored("FREQ=MONTHLY;BYDAY=MO;BYMONTH=1,6;BYSETPOS=1", Frequency.Monthly),
            stored("FREQ=DAILY;BYHOUR=8,14,20;BYMINUTE=0", Frequency.Daily),
            stored("FREQ=MONTHLY;BYMONTHDAY=15,28,29,30;BYSETPOS=1,-1", Frequency.Monthly),
            stored("FREQ=MONTHLY;BYMONTHDAY=28,29,30;BYMONTH=1,2;BYSETPOS=-1", Frequency.Monthly),
        ).forEach { rule ->
            val parts = assertNotNull(app.kadans.ui.todos.RuleSummary.parts(rule))
            val back = sent(draft(rule).state)
            assertEquals(rule.frequency, back.frequency, rule.rrule)
            assertEquals(rule.interval, back.interval, rule.rrule)
            assertEquals(parts.byDay?.toSet(), back.byDayOfWeek?.toSet(), rule.rrule)
            assertEquals(parts.byMonthDay?.toSet(), back.byMonthDay?.toSet(), rule.rrule)
            assertEquals(parts.bySetPos, back.bySetPos, rule.rrule)
            assertEquals(parts.byMonth?.toSet(), back.byMonth?.toSet(), rule.rrule)
            assertEquals(parts.byHour, back.byHour, rule.rrule)
        }
    }

    @Test
    fun an_end_comes_back_as_its_day_and_its_time_unless_it_is_the_end_of_that_day() {
        val endOfDay = draft(stored("FREQ=DAILY;UNTIL=20270401T035900Z", Frequency.Daily, until = "2027-04-01T03:59:00Z")).state
        assertEquals(EndMode.OnDate, endOfDay.endMode)
        assertEquals(LocalDate(2027, 3, 31), endOfDay.untilDate)
        assertNull(endOfDay.untilTime)

        val atSix = draft(stored("FREQ=DAILY;UNTIL=20270331T220000Z", Frequency.Daily, until = "2027-03-31T22:00:00Z")).state
        assertEquals(LocalTime(18, 0), atSix.untilTime)
    }

    @Test
    fun a_rule_made_elsewhere_keeps_its_frequency_and_says_so() {
        val firstMonday = draft(stored("FREQ=MONTHLY;BYDAY=1MO", Frequency.Monthly))

        assertFalse(firstMonday.worded)
        assertEquals(Frequency.Monthly, firstMonday.state.frequency)
    }

    @Test
    fun the_first_start_is_the_first_time_still_ahead() {
        val pills = CreateTodoUiState(
            title = "Pills", mode = TodoMode.Recurring, date = monday, frequency = Frequency.Daily,
            times = listOf(LocalTime(8, 0), LocalTime(14, 0), LocalTime(20, 0)),
        )
        val afternoon = LocalDateTime(monday, LocalTime(15, 0)).toInstant(portAuPrince)
        // Set up (or edited) at 15:00: today's 20:00 is the first, so it is kept, and BYHOUR covers the days after.
        assertEquals(LocalDateTime(monday, LocalTime(20, 0)), CreateTodoViewModel.firstStart(pills, portAuPrince, afternoon))
        assertTrue(CreateTodoViewModel.ruleReady(pills, portAuPrince, afternoon))

        // A weekly rule whose time has gone today starts on its next day.
        val weekly = CreateTodoUiState(title = "Gym", mode = TodoMode.Recurring, date = monday, time = LocalTime(7, 0), frequency = Frequency.Weekly)
        assertEquals(LocalDateTime(LocalDate(2027, 1, 11), LocalTime(7, 0)), CreateTodoViewModel.firstStart(weekly, portAuPrince, afternoon))

        // A one-time todo for a moment gone cannot be sent, and says so.
        val once = weekly.copy(mode = TodoMode.OneTime)
        assertTrue(CreateTodoViewModel.startPassed(once, portAuPrince, afternoon))
        assertFalse(CreateTodoViewModel.ruleReady(once, portAuPrince, afternoon))

        // Nor can a rule ending before its first time.
        val endsEarly = pills.copy(endMode = EndMode.OnDate, untilDate = monday, untilTime = LocalTime(18, 0))
        assertFalse(CreateTodoViewModel.ruleReady(endsEarly, portAuPrince, afternoon))
    }

    @Test
    fun an_edit_starts_from_the_next_occurrence_nobody_moved() {
        fun occurrence(at: String, moved: Boolean) = TodoOccurrenceResponse(
            id = at, todoId = "t1", todoTitle = "Alarm", scheduledAt = Instant.parse(at), originalScheduledAt = Instant.parse(at),
            status = OccurrenceStatus.Pending, isRescheduled = moved,
        )
        val now = Instant.parse("2027-01-04T12:30:00Z")
        val pending = listOf(occurrence("2027-01-05T12:00:00Z", moved = true), occurrence("2027-01-06T12:00:00Z", moved = false))

        assertEquals(LocalDate(2027, 1, 6), EditTodoViewModel.firstOn(pending, now, portAuPrince))
    }

    // ---- The edit screen against a fake server ----

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun before() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun after() = Dispatchers.resetMain()

    private val todoJson = """{"id":"t1","title":"Work alarm","description":"","status":"Scheduled","notificationEnabled":true,""" +
        """"notifyBeforeInMinutes":15,"recurrenceRule":{"rrule":"FREQ=WEEKLY;COUNT=20;BYDAY=MO,TU,WE,TH,FR",""" +
        """"timeZoneId":"America/Port-au-Prince","startDate":"2027-01-04T12:00:00Z","frequency":"Weekly","interval":1,""" +
        """"count":20,"isOneTime":false,"exceptions":[],"remaining":12},"remarks":[],""" +
        """"createdAt":"2026-10-01T00:00:00Z","updatedAt":"2026-10-01T00:00:00Z"}"""

    private val occurrencesJson = """[{"id":"o1","todoId":"t1","todoTitle":"Work alarm","scheduledAt":"2027-01-12T12:00:00Z",""" +
        """"originalScheduledAt":"2027-01-12T12:00:00Z","status":"Pending"}]"""

    private fun api(sent: MutableList<String>) = KadansApi.create(
        "http://test",
        engine = MockEngine { request ->
            val path = request.url.encodedPath
            val body = when {
                request.method == HttpMethod.Put && path.endsWith("todos/t1") -> todoJson.also { sent += (request.body as TextContent).text }
                path.endsWith("todos/t1/occurrences") -> occurrencesJson
                path.endsWith("todos/t1") -> todoJson
                else -> "{}"
            }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        },
    )

    @Test
    fun the_edit_screen_sends_the_rule_only_once_it_is_changed() = runTest(dispatcher) {
        val sent = mutableListOf<String>()
        val viewModel = EditTodoViewModel(api(sent), "t1")
        val loaded = viewModel.state.first { !it.isLoading }
        val rule = assertNotNull(loaded.rule)
        assertEquals(LocalDate(2027, 1, 12), rule.date)
        assertEquals(12, rule.count)

        viewModel.update { it.copy(title = "Work alarm (new office)") }
        viewModel.save()
        viewModel.state.first { !it.isSaving }
        assertEquals(JsonNull, Json.parseToJsonElement(sent.last()).jsonObject["recurrenceRule"] ?: JsonNull, "left alone, the rule is not sent")

        viewModel.updateRule { CreateTodoViewModel.toggleDay(it, ApiDayOfWeek.Saturday) }
        assertTrue(viewModel.state.value.ruleChanged)
        viewModel.save()
        viewModel.state.first { !it.isSaving }
        val changed = Json.parseToJsonElement(sent.last()).jsonObject["recurrenceRule"]!!.jsonObject
        assertEquals(6, changed["byDayOfWeek"]!!.jsonArray.size)
        assertEquals(12, changed["count"]!!.jsonPrimitive.content.toInt())
        // 07:00 in the rule's own zone, whatever zone this machine is in (CI's is UTC).
        assertEquals("America/Port-au-Prince", changed["timeZone"]!!.jsonPrimitive.content)
        assertEquals(Instant.parse("2027-01-12T12:00:00Z"), Instant.parse(changed["startDate"]!!.jsonPrimitive.content))
    }
}

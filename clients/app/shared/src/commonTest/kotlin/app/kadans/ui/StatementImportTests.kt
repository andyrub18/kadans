package app.kadans.ui

import app.kadans.TestMain
import app.kadans.api.KadansApi
import app.kadans.api.model.BudgetTransactionKind
import app.kadans.files.FilePorts
import app.kadans.files.PickedFile
import app.kadans.files.SaveResult
import app.kadans.statement.ImportKeys
import app.kadans.statement.StatementText.DateOrder
import app.kadans.ui.budget.ImportProblem
import app.kadans.ui.budget.ImportStep
import app.kadans.ui.budget.StatementImportUiState
import app.kadans.ui.budget.StatementImportViewModel
import app.kadans.ui.settings.DataExportViewModel
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A bank statement brought into the budget, and a copy of the person's data saved (ARCHITECTURE → "Statements in, data out"). */
class StatementImportTests {
    private val dispatcher = StandardTestDispatcher()
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private val zone = TimeZone.of("America/Port-au-Prince")
    private val today = LocalDate(2026, 11, 16)

    @BeforeTest
    fun before() = TestMain.set(dispatcher)

    @AfterTest
    fun after() = TestMain.reset()

    private val accounts =
        """[{"id":"a1","name":"Cash","currency":"Htg","type":"Cash","initialBalance":0,"balance":0,"createdAt":"2026-09-01T00:00:00Z","updatedAt":"2026-09-01T00:00:00Z"},""" +
            """{"id":"a3","name":"Card","currency":"Usd","type":"Card","initialBalance":0,"balance":0,"createdAt":"2026-09-01T00:00:00Z","updatedAt":"2026-09-01T00:00:00Z"}]"""

    private val categories =
        """[{"id":"c1","name":"Food","kind":"Expense"},{"id":"c2","name":"Salary","kind":"Income"},{"id":"c3","name":"Transport","kind":"Expense"}]"""

    /** The server: what it was asked, and what it holds (the keys an account already has, the person's filed texts). */
    private inner class Server(
        var known: List<String> = emptyList(),
        var suggested: String = "[]",
        /** The import requests, from the first, that fail. */
        var failing: Set<Int> = emptySet(),
        val export: ByteArray = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 1, 2, 3),
    ) {
        val calls = mutableListOf<String>()
        val imports = mutableListOf<JsonObject>()
        private var importsAsked = 0

        /** Everything a request carried towards the server: its address with its question, and its body. */
        val sent = mutableListOf<String>()

        val api: KadansApi = KadansApi.create(
            "http://test",
            engine = MockEngine { request ->
                val call = "${request.method.value} ${request.url.encodedPath}"
                calls += call
                val text = (request.body as? TextContent)?.text
                sent += request.url.toString() + " " + text.orEmpty()
                val body = text?.let { Json.parseToJsonElement(it).jsonObject }
                when {
                    call == "GET /users/me/export" -> respond(export, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/zip"))
                    call == "GET /budget/transactions/import/known" ->
                        respond("""{"importKeys":[${known.joinToString(",") { "\"$it\"" }}],"categories":$suggested}""", HttpStatusCode.OK, jsonHeaders)
                    call == "POST /budget/transactions/import/" -> {
                        val nth = importsAsked++
                        if (nth in failing) {
                            respondError(HttpStatusCode.ServiceUnavailable)
                        } else {
                            imports += body!!
                            respond("""{"imported":${body["movements"]!!.jsonArray.size},"skipped":0}""", HttpStatusCode.OK, jsonHeaders)
                        }
                    }
                    call.startsWith("GET /budget/accounts") -> respond(accounts, HttpStatusCode.OK, jsonHeaders)
                    call.startsWith("GET /budget/categories") -> respond(categories, HttpStatusCode.OK, jsonHeaders)
                    else -> respond("{}", HttpStatusCode.OK, jsonHeaders)
                }
            },
        )
    }

    /** The system's dialogs: the file the person picks, and what was saved where they said. */
    private class Files(var picked: PickedFile? = null, var saves: SaveResult = SaveResult.Saved) : FilePorts {
        override val available = true
        var saved: Triple<String, String, ByteArray>? = null

        override suspend fun open(title: String, maxBytes: Int): PickedFile? = picked

        override suspend fun save(title: String, suggestedName: String, mimeType: String, bytes: ByteArray): SaveResult {
            if (saves == SaveResult.Saved) saved = Triple(suggestedName, mimeType, bytes)
            return saves
        }
    }

    private fun file(name: String, text: String) = PickedFile(name, text.encodeToByteArray())

    private fun viewModel(server: Server, files: Files, fallback: DateOrder = DateOrder.DayMonthYear) =
        StatementImportViewModel(server.api, files, fallbackOrder = { fallback }, background = dispatcher, timeZone = zone, today = { today })

    private suspend fun StatementImportViewModel.at(predicate: (StatementImportUiState) -> Boolean) = state.first { !it.isLoading && predicate(it) }

    private val bank = """
        Date;Libellé;Débit;Crédit;Solde
        03/11/2026;MARCHE EN FER;1 250,00;;48 750,00
        05/11/2026;SALAIRE NOVEMBRE;;85 000,00;133 750,00
        14/11/2026;MARCHE EN FER;980,50;;132 769,50
    """.trimIndent()

    @Test
    fun a_csv_statement_goes_from_its_columns_to_its_lines_to_the_account() = runTest(dispatcher) {
        // The account already has the salary (last month's statement overlapped), and the market was "Food" before.
        val server = Server(suggested = """[{"kind":"Expense","note":"MARCHE EN FER","categoryId":"gone"},{"kind":"Expense","note":"MARCHE EN FER","categoryId":"c1"},{"kind":"Income","note":"SALAIRE NOVEMBRE","categoryId":"c1"}]""")
        val files = Files(file("releve-novembre.csv", bank))
        val viewModel = viewModel(server, files)
        viewModel.at { true }
        viewModel.selectAccount("a1")
        viewModel.choose("Choose")

        // The columns, guessed: the person sees the first lines read that way before anything else.
        val columns = viewModel.at { it.step == ImportStep.Columns }
        assertEquals("releve-novembre.csv", columns.fileName)
        assertEquals(0, columns.mapping.date)
        assertEquals(1, columns.mapping.text)
        assertEquals(2 to 3, columns.mapping.moneyOut to columns.mapping.moneyIn)
        assertEquals(DateOrder.DayMonthYear, columns.mapping.order)
        assertTrue(columns.datesNeedOrder)
        assertFalse(columns.orderUnsure) // the 14th says which number is the day
        assertEquals(listOf(-125000L, 8500000L, -98050L), columns.read!!.lines.map { it.cents })
        assertTrue(columns.canContinue)

        // The account already has the salary: the server says which keys it holds, and the lines are sorted here.
        server.known = listOf(ImportKeys.of(columns.read!!.lines)[1], "l1-of-another-statement")
        viewModel.review()
        val review = viewModel.at { it.step == ImportStep.Review && !it.isChecking }

        // Asked with the account alone. Nothing of the statement has left the device: not a text, not an amount, not
        // a day, not even a line's key.
        assertTrue(server.calls.none { it.startsWith("POST") })
        val asked = server.sent.single { "import/known" in it }
        assertTrue(asked.trim().endsWith("?accountId=a1"), asked)
        for (line in review.lines) {
            assertTrue(server.sent.none { line.key in it || "MARCHE" in it || "SALAIRE" in it }, server.sent.toString())
        }
        // The salary is set aside; the market's lines take the category it had, never one the person no longer has
        // (nor, for the salary, an expense's category).
        assertEquals(listOf(false, true, false), review.lines.map { it.already })
        assertEquals(listOf(true, false, true), review.lines.map { it.keep })
        assertEquals(listOf("c1", null, "c1"), review.lines.map { it.categoryId })
        assertEquals(2, review.kept)
        assertEquals(1, review.alreadyThere)
        assertTrue(review.canImport)

        viewModel.import()
        val done = viewModel.at { it.step == ImportStep.Done }
        assertEquals(2, done.imported)

        val sent = server.imports.single()
        assertEquals("a1", sent["accountId"]!!.jsonPrimitive.content)
        val movements = sent["movements"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("Expense", "Expense"), movements.map { it["kind"]!!.jsonPrimitive.content })
        assertEquals(listOf(1250.0, 980.5), movements.map { it["amount"]!!.jsonPrimitive.content.toDouble() })
        assertEquals(listOf("MARCHE EN FER", "MARCHE EN FER"), movements.map { it["note"]!!.jsonPrimitive.content })
        assertEquals(listOf("c1", "c1"), movements.map { it["categoryId"]!!.jsonPrimitive.content })
        // Each line on its day at noon, where the person is; and the two market lines under two keys.
        assertEquals(LocalDate(2026, 11, 3).atStartOfDayIn(zone) + 12.hours, Instant.parse(movements[0]["occurredAt"]!!.jsonPrimitive.content))
        assertEquals(2, movements.map { it["importKey"]!!.jsonPrimitive.content }.toSet().size)
        assertEquals(listOf(review.lines[0].key, review.lines[2].key), movements.map { it["importKey"]!!.jsonPrimitive.content })
    }

    @Test
    fun the_person_corrects_what_was_guessed_and_the_lines_follow() = runTest(dispatcher) {
        // A card's statement: purchases above zero, and dates that say no order themselves.
        val card = "Date,Details,Amount\n03/11/2026,SUPERMARKET,42.17\n05/11/2026,PAYMENT RECEIVED,-500.00\n"
        val viewModel = viewModel(Server(), Files(file("card.csv", card)), fallback = DateOrder.MonthDayYear)
        viewModel.at { true }
        viewModel.selectAccount("a3")
        viewModel.choose("Choose")

        val guessed = viewModel.at { it.step == ImportStep.Columns }
        // Read the American way, 11 March is the only reading that is not in the future… of 16 November: both are past.
        assertTrue(guessed.orderUnsure)
        assertEquals(DateOrder.MonthDayYear, guessed.mapping.order)
        assertEquals(LocalDate(2026, 3, 11), guessed.read!!.lines.first().date)
        assertEquals(listOf(4217L, -50000L), guessed.read!!.lines.map { it.cents })

        viewModel.map { it.copy(order = DateOrder.DayMonthYear) }
        viewModel.map { it.copy(positiveIsIncome = false) }
        val corrected = viewModel.at { it.read?.lines?.firstOrNull()?.cents == -4217L }
        assertFalse(corrected.orderUnsure) // the person said
        assertEquals(LocalDate(2026, 11, 3), corrected.read!!.lines.first().date)
        assertEquals(listOf(-4217L, 50000L), corrected.read!!.lines.map { it.cents })

        // Without a column for the amount there is nothing to go on with.
        viewModel.map { it.copy(amount = null) }
        val incomplete = viewModel.at { it.read?.lines?.isEmpty() == true }
        assertFalse(incomplete.canContinue)
    }

    @Test
    fun an_ofx_file_needs_no_columns_and_says_its_currency() = runTest(dispatcher) {
        val ofx = "OFXHEADER:100\n<OFX><CURDEF>USD<BANKTRANLIST>\n" +
            "<STMTTRN><TRNTYPE>DEBIT<DTPOSTED>20261103<TRNAMT>-42.17<FITID>2026110300001<NAME>GIANT MARKET\n" +
            "<STMTTRN><TRNTYPE>CREDIT<DTPOSTED>20261116<TRNAMT>1500.00<FITID>2026111600002<NAME>PAYROLL\n</BANKTRANLIST></OFX>"
        val server = Server()
        val viewModel = viewModel(server, Files(file("activity.qfx", ofx)))
        viewModel.at { true }
        viewModel.selectAccount("a1")
        viewModel.choose("Choose")

        val review = viewModel.at { it.step == ImportStep.Review && !it.isChecking }
        assertNull(review.table)
        assertEquals(listOf(BudgetTransactionKind.Expense, BudgetTransactionKind.Income), review.lines.map { it.kind })
        assertTrue(review.lines.all { it.key.startsWith("b1-") }) // named by the bank's own id
        // Dollars into a gourde account: said, not refused. On the dollar card, nothing to say.
        assertTrue(review.currencyDiffers)
        assertFalse(review.copy(accountId = "a3").currencyDiffers)

        // Back from the lines of such a file is back to the file itself.
        assertTrue(viewModel.back())
        assertEquals(ImportStep.Choose, viewModel.state.value.step)
        assertFalse(viewModel.back()) // and from there Back leaves the screen
    }

    @Test
    fun a_camt_statement_goes_straight_to_its_lines_and_books_them() = runTest(dispatcher) {
        // Two accounts in one file, as a bank's bulk download makes it: the first account's two lines are read.
        fun statement(account: String, entries: String) =
            "<Stmt><Acct><Id><IBAN>$account</IBAN></Id><Ccy>EUR</Ccy></Acct>$entries</Stmt>"
        fun entry(amount: String, way: String, day: String, reference: String, about: String) =
            "<Ntry><Amt Ccy=\"EUR\">$amount</Amt><CdtDbtInd>$way</CdtDbtInd><Sts>BOOK</Sts><BookgDt><Dt>$day</Dt></BookgDt>" +
                "<AcctSvcrRef>$reference</AcctSvcrRef><AddtlNtryInf>$about</AddtlNtryInf></Ntry>"
        val camt = """<?xml version="1.0"?><Document xmlns="urn:iso:std:iso:20022:tech:xsd:camt.053.001.02"><BkToCstmrStmt>""" +
            statement("NL00BANK0123456789", entry("42.17", "DBIT", "2026-11-03", "2026110300001", "SUPERMARKT") + entry("1500.00", "CRDT", "2026-11-13", "2026111300002", "SALARIS")) +
            statement("NL00BANK0000000002", entry("777.00", "CRDT", "2026-11-10", "2026111000003", "SPAARREKENING")) +
            "</BkToCstmrStmt></Document>"
        val server = Server()
        val viewModel = viewModel(server, Files(file("export.xml", camt)))
        viewModel.at { true }
        viewModel.selectAccount("a1")
        viewModel.choose("Choose")

        val review = viewModel.at { it.step == ImportStep.Review && !it.isChecking }
        assertNull(review.table) // a standard's file: no columns to ask about
        assertEquals(listOf(-4217L, 150000L), review.lines.map { it.line.cents })
        assertTrue(review.lines.all { it.key.startsWith("b1-") }) // named by the bank's own references
        assertTrue(review.otherAccounts) // the savings account's line is not mixed in, and the screen says so
        assertTrue(review.currencyDiffers) // euros into a gourde account: said, not refused

        viewModel.import()
        viewModel.at { it.step == ImportStep.Done }
        val movements = server.imports.single()["movements"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("Expense" to 42.17, "Income" to 1500.0), movements.map { it["kind"]!!.jsonPrimitive.content to it["amount"]!!.jsonPrimitive.content.toDouble() })
        assertEquals(listOf("SUPERMARKT", "SALARIS"), movements.map { it["note"]!!.jsonPrimitive.content })
    }

    @Test
    fun a_file_kadans_cannot_read_says_why_and_sends_nothing() = runTest(dispatcher) {
        val server = Server()
        val files = Files()
        val viewModel = viewModel(server, files)
        viewModel.at { true }
        viewModel.selectAccount("a1")

        suspend fun problemOf(file: PickedFile): ImportProblem? {
            files.picked = file
            viewModel.choose("Choose")
            dispatcher.scheduler.advanceUntilIdle()
            return viewModel.state.value.also { assertEquals(ImportStep.Choose, it.step) }.problem
        }
        assertEquals(ImportProblem.Pdf, problemOf(file("statement.pdf", "%PDF-1.7 …")))
        assertEquals(ImportProblem.Spreadsheet, problemOf(PickedFile("statement.xlsx", byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14))))
        assertEquals(ImportProblem.TooLarge, problemOf(PickedFile("ten-years.csv", null, tooLarge = true)))
        assertEquals(ImportProblem.Unreadable, problemOf(PickedFile("gone.csv", null)))
        assertEquals(ImportProblem.Unreadable, problemOf(PickedFile("photo.png", byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0, 0))))
        assertEquals(ImportProblem.NoLines, problemOf(file("notes.txt", "just a note to myself")))
        assertEquals(ImportProblem.NoLines, problemOf(file("empty.ofx", "OFXHEADER:100\n<OFX><BANKTRANLIST></BANKTRANLIST></OFX>")))
        // A picker closed without a file changes nothing.
        files.picked = null
        viewModel.choose("Choose")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(ImportProblem.NoLines, viewModel.state.value.problem)
        assertTrue(server.calls.none { "import" in it })
    }

    @Test
    fun a_long_statement_goes_in_parts_and_a_part_that_fails_is_the_only_one_sent_again() = runTest(dispatcher) {
        // 1,200 lines: three requests of at most 500. The second fails once.
        val long = "Date,Details,Amount\n" + (1..1200).joinToString("\n") { "2026-10-${(it % 28 + 1).toString().padStart(2, '0')},SHOP $it,-${it}.00" }
        val server = Server(failing = setOf(1))
        val viewModel = viewModel(server, Files(file("year.csv", long)))
        viewModel.at { true }
        viewModel.selectAccount("a1")
        viewModel.choose("Choose")
        viewModel.at { it.step == ImportStep.Columns }
        viewModel.review()
        assertEquals(1200, viewModel.at { it.step == ImportStep.Review && !it.isChecking }.kept)

        viewModel.import()
        val stopped = viewModel.at { !it.isImporting && (it.error != null || it.errorCode != null) }
        assertEquals(ImportStep.Review, stopped.step)
        assertEquals(500, stopped.imported)
        // What the first request booked is in the account: set aside, and no longer counted as to send.
        assertEquals(500, stopped.alreadyThere)
        assertEquals(700, stopped.kept)
        assertTrue(stopped.canImport)

        viewModel.import()
        val done = viewModel.at { it.step == ImportStep.Done }
        assertEquals(1200, done.imported)
        assertEquals(listOf(500, 500, 200), server.imports.map { it["movements"]!!.jsonArray.size })
        // No line was sent twice.
        val keys = server.imports.flatMap { sent -> sent["movements"]!!.jsonArray.map { it.jsonObject["importKey"]!!.jsonPrimitive.content } }
        assertEquals(1200, keys.toSet().size)
        assertFalse(viewModel.back()) // done: Back leaves the screen
    }

    @Test
    fun a_category_chosen_for_a_line_goes_to_the_lines_that_read_the_same() = runTest(dispatcher) {
        val server = Server()
        val viewModel = viewModel(server, Files(file("releve.csv", bank)))
        viewModel.at { true }
        viewModel.selectAccount("a1")
        viewModel.choose("Choose")
        viewModel.at { it.step == ImportStep.Columns }
        viewModel.review()
        val review = viewModel.at { it.step == ImportStep.Review && !it.isChecking }
        val (market, salary, marketAgain) = review.lines.map { it.key }

        assertEquals(1, viewModel.sameAs(market))
        assertEquals(0, viewModel.sameAs(salary))
        assertEquals(listOf("c1", "c3"), review.categoriesFor(BudgetTransactionKind.Expense).map { it.id })
        assertEquals(listOf("c2"), review.categoriesFor(BudgetTransactionKind.Income).map { it.id })

        viewModel.categorize(market, "c1", sameToo = true)
        assertEquals(listOf("c1", null, "c1"), viewModel.state.value.lines.map { it.categoryId })
        viewModel.categorize(marketAgain, "c3", sameToo = false)
        assertEquals(listOf("c1", null, "c3"), viewModel.state.value.lines.map { it.categoryId })

        // Lines left out are not sent; with none kept there is nothing to import.
        viewModel.keep(market, false)
        assertEquals(2, viewModel.state.value.kept)
        viewModel.keepAll(false)
        assertFalse(viewModel.state.value.canImport)
        viewModel.keepAll(true)
        viewModel.keep(salary, false)
        viewModel.import()
        viewModel.at { it.step == ImportStep.Done }
        assertEquals(listOf("c1", "c3"), server.imports.single()["movements"]!!.jsonArray.map { it.jsonObject["categoryId"]!!.jsonPrimitive.content })
    }

    @Test
    fun a_check_that_fails_keeps_the_import_closed_until_it_answers() = runTest(dispatcher) {
        val server = Server()
        val broken = KadansApi.create("http://test", engine = MockEngine { request ->
            val path = request.url.encodedPath
            when {
                path.startsWith("/budget/accounts") -> respond(accounts, HttpStatusCode.OK, jsonHeaders)
                path.startsWith("/budget/categories") -> respond(categories, HttpStatusCode.OK, jsonHeaders)
                else -> respond("""{"title":"Server busy","status":503,"errorCode":"10001"}""", HttpStatusCode.ServiceUnavailable, jsonHeaders)
            }
        })
        val viewModel = StatementImportViewModel(broken, Files(file("releve.csv", bank)), background = dispatcher, timeZone = zone, today = { today })
        viewModel.at { true }
        viewModel.selectAccount("a1")
        viewModel.choose("Choose")
        viewModel.at { it.step == ImportStep.Columns }
        viewModel.review()

        val failed = viewModel.at { it.step == ImportStep.Review && it.checkFailed }
        // Without knowing what the account already has, nothing is offered to send.
        assertFalse(failed.canImport)
        assertTrue(server.imports.isEmpty())
        // Back to the columns, the lines are as they were read.
        assertTrue(viewModel.back())
        assertEquals(ImportStep.Columns, viewModel.state.value.step)
        assertEquals(3, viewModel.state.value.read!!.lines.size)
    }

    @Test
    fun a_copy_of_my_data_is_fetched_then_saved_where_the_person_says() = runTest(dispatcher) {
        val server = Server()
        val files = Files()
        val viewModel = DataExportViewModel(server.api, files, today = { today })

        viewModel.save("Save")
        val saved = viewModel.state.first { !it.isBusy }
        assertTrue(saved.saved)
        val (name, type, bytes) = files.saved!!
        assertEquals("kadans-export-2026-11-16.zip", name)
        assertEquals("application/zip", type)
        assertContentEquals(server.export, bytes)

        // The dialog closed without a place: nothing saved, nothing to complain about.
        files.saves = SaveResult.Cancelled
        viewModel.save("Save")
        dispatcher.scheduler.advanceUntilIdle()
        val cancelled = viewModel.state.first { !it.isBusy }
        assertFalse(cancelled.saved)
        assertFalse(cancelled.saveFailed)
        assertNull(cancelled.errorCode)

        // A place that would not take the file says so.
        files.saves = SaveResult.Failed
        viewModel.save("Save")
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(viewModel.state.first { !it.isBusy }.saveFailed)
    }
}

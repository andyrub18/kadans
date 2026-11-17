package app.kadans.ui.budget

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kadans.api.BudgetApi
import app.kadans.api.KadansApi
import app.kadans.api.KadansApiException
import app.kadans.api.model.AccountResponse
import app.kadans.api.model.BudgetTransactionKind
import app.kadans.api.model.CategoryKind
import app.kadans.api.model.CategoryResponse
import app.kadans.api.model.ImportTransactions
import app.kadans.api.model.ImportedMovement
import app.kadans.files.FilePorts
import app.kadans.files.PickedFile
import app.kadans.statement.Camt
import app.kadans.statement.CsvFile
import app.kadans.statement.CsvMapping
import app.kadans.statement.CsvTable
import app.kadans.statement.ImportKeys
import app.kadans.statement.Ofx
import app.kadans.statement.StatementKind
import app.kadans.statement.StatementLine
import app.kadans.statement.StatementRead
import app.kadans.statement.StatementText
import app.kadans.statement.StatementText.DateOrder
import app.kadans.statement.Statements
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.todayIn

/** Where the person is in bringing a statement in. */
enum class ImportStep { Choose, Columns, Review, Done }

/** Why the file picked gave nothing to import. */
enum class ImportProblem { TooLarge, Pdf, Spreadsheet, Unreadable, NoLines }

/** One line of the statement on the list the person checks. */
data class ReviewLine(
    val line: StatementLine,
    /** What names the line for the account (`ImportKeys`). */
    val key: String,
    /** The account has it already (this statement imported before, or one that overlaps it): it is never sent again. */
    val already: Boolean = false,
    val keep: Boolean = true,
    val categoryId: String? = null,
) {
    val kind: BudgetTransactionKind get() = if (line.cents > 0) BudgetTransactionKind.Income else BudgetTransactionKind.Expense

    /** As a movement holds it: above zero, its kind saying which way. */
    val amount: Double get() = abs(line.cents) / 100.0
}

data class StatementImportUiState(
    val isLoading: Boolean = true,
    val loadFailed: Boolean = false,
    val error: String? = null,
    val errorCode: String? = null,
    /** The accounts a statement can be booked on, and the categories still in use. */
    val accounts: List<AccountResponse> = emptyList(),
    val categories: List<CategoryResponse> = emptyList(),
    val accountId: String? = null,
    val step: ImportStep = ImportStep.Choose,
    val fileName: String = "",
    val problem: ImportProblem? = null,
    val isReading: Boolean = false,
    /** A CSV file: its table, what each column is, and its lines read that way. */
    val table: CsvTable? = null,
    val mapping: CsvMapping = CsvMapping(),
    /** Its dates are numbers with the year behind: there is an order to say. */
    val datesNeedOrder: Boolean = false,
    /** …and they do not say it themselves: the person checks. */
    val orderUnsure: Boolean = false,
    val read: StatementRead? = null,
    val lines: List<ReviewLine> = emptyList(),
    val unread: Int = 0,
    /** The file holds more lines than are read at once: the rest was left out, and the screen says so. */
    val truncated: Boolean = false,
    /** The file holds several accounts' statements: only the first account's lines are here, and the screen says so. */
    val otherAccounts: Boolean = false,
    /** The currency the file says it is in, when it says. */
    val statementCurrency: String? = null,
    val isChecking: Boolean = false,
    val checkFailed: Boolean = false,
    val isImporting: Boolean = false,
    /** Lines sent of [toSend], while importing. */
    val sent: Int = 0,
    val toSend: Int = 0,
    val imported: Int = 0,
    val skipped: Int = 0,
) {
    val account: AccountResponse? get() = accounts.firstOrNull { it.id == accountId }

    /** The lines that will be sent. */
    val kept: Int get() = lines.count { it.keep && !it.already }

    val alreadyThere: Int get() = lines.count { it.already }

    val canContinue: Boolean get() = mapping.complete && read?.lines?.isNotEmpty() == true

    val canImport: Boolean get() = kept > 0 && !isChecking && !checkFailed && !isImporting

    val currencyDiffers: Boolean
        get() = statementCurrency != null && account?.currency?.name?.equals(statementCurrency, ignoreCase = true) == false

    /** The categories a line of [kind] can take. */
    fun categoriesFor(kind: BudgetTransactionKind): List<CategoryResponse> {
        val wanted = if (kind == BudgetTransactionKind.Income) CategoryKind.Income else CategoryKind.Expense
        return categories.filter { it.kind == wanted }
    }
}

/**
 * A bank statement brought into the budget (ARCHITECTURE → "Statements in, data out"). The file is read here, on the
 * device; what goes to the server is the lines the person kept, each with a key that names it, so an account books a
 * line once however often its statement is imported. Three steps: the account and the file; for a CSV file, what each
 * column is (guessed, then corrected); the lines to check, those the account already has set aside.
 */
class StatementImportViewModel(
    private val api: KadansApi,
    private val files: FilePorts,
    /** How a date's numbers are read when neither the file nor the calendar tells. */
    private val fallbackOrder: () -> DateOrder = { DateOrder.DayMonthYear },
    private val background: CoroutineDispatcher = Dispatchers.Default,
    private val timeZone: TimeZone = TimeZone.currentSystemDefault(),
    private val today: () -> LocalDate = { Clock.System.todayIn(timeZone) },
) : ViewModel() {
    private val _state = MutableStateFlow(StatementImportUiState())
    val state: StateFlow<StatementImportUiState> = _state.asStateFlow()

    private var pickJob: Job? = null
    private var readJob: Job? = null
    private var checkJob: Job? = null

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(isLoading = true, loadFailed = false, error = null, errorCode = null) }
        viewModelScope.launch {
            try {
                val accounts = api.budget.accounts()
                val categories = api.budget.categories()
                _state.update {
                    it.copy(
                        isLoading = false, accounts = accounts, categories = categories,
                        accountId = it.accountId?.takeIf { id -> accounts.any { a -> a.id == id } } ?: accounts.singleOrNull()?.id,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: KadansApiException) {
                _state.update { it.copy(isLoading = false, loadFailed = true, error = e.message, errorCode = e.errorCode) }
            } catch (_: Exception) {
                _state.update { it.copy(isLoading = false, loadFailed = true, error = null, errorCode = "network") }
            }
        }
    }

    fun selectAccount(id: String) = _state.update { it.copy(accountId = id) }

    /**
     * The system's picker, then the file read. Asked again while a picker is still open somewhere, the earlier
     * question is dropped: its answer, if it ever comes, is nobody's.
     */
    fun choose(dialogTitle: String) {
        pickJob?.cancel()
        pickJob = viewModelScope.launch {
            val file = files.open(dialogTitle, StatementText.MAX_BYTES) ?: return@launch
            opened(file)
        }
    }

    internal suspend fun opened(file: PickedFile) {
        _state.update { it.copy(fileName = file.name, problem = null, isReading = true, error = null, errorCode = null) }
        val bytes = file.bytes
        val parsed = if (bytes == null) Parsed.Nothing(if (file.tooLarge) ImportProblem.TooLarge else ImportProblem.Unreadable)
        else withContext(background) { parse(bytes) }
        when (parsed) {
            is Parsed.Nothing -> _state.update { it.copy(isReading = false, problem = parsed.problem) }
            is Parsed.Lines -> {
                _state.update { it.copy(isReading = false, table = null, read = null) }
                review(parsed.read)
            }
            is Parsed.Table -> _state.update {
                it.copy(
                    isReading = false, step = ImportStep.Columns, table = parsed.table, mapping = parsed.mapping, read = parsed.read,
                    datesNeedOrder = parsed.needsOrder, orderUnsure = parsed.unsure,
                )
            }
        }
    }

    private sealed interface Parsed {
        class Nothing(val problem: ImportProblem) : Parsed
        class Lines(val read: StatementRead) : Parsed
        class Table(val table: CsvTable, val mapping: CsvMapping, val read: StatementRead, val needsOrder: Boolean, val unsure: Boolean) : Parsed
    }

    /** What the file is, told from what is in it, and its lines. Nothing here trusts it: it is somebody else's. */
    private fun parse(bytes: ByteArray): Parsed = try {
        when (Statements.kind(bytes)) {
            StatementKind.Pdf -> Parsed.Nothing(ImportProblem.Pdf)
            StatementKind.Spreadsheet -> Parsed.Nothing(ImportProblem.Spreadsheet)
            StatementKind.Unreadable -> Parsed.Nothing(ImportProblem.Unreadable)
            StatementKind.Ofx -> Ofx.read(StatementText.decode(bytes)).let { if (it.lines.isEmpty()) Parsed.Nothing(ImportProblem.NoLines) else Parsed.Lines(it) }
            StatementKind.Camt -> Camt.read(StatementText.decode(bytes)).let { if (it.lines.isEmpty()) Parsed.Nothing(ImportProblem.NoLines) else Parsed.Lines(it) }
            StatementKind.Csv -> {
                val table = CsvFile.table(StatementText.decode(bytes))
                if (table.rows.isEmpty()) {
                    Parsed.Nothing(ImportProblem.NoLines)
                } else {
                    val mapping = CsvFile.guess(table, fallbackOrder(), today())
                    val dates = mapping.date?.let(table::column).orEmpty()
                    val needsOrder = StatementText.needsOrder(dates)
                    Parsed.Table(table, mapping, CsvFile.read(table, mapping), needsOrder, needsOrder && StatementText.dateOrder(dates, today()) == null)
                }
            }
        }
    } catch (_: Exception) {
        // Whatever a file holds, reading it never takes the screen down.
        Parsed.Nothing(ImportProblem.Unreadable)
    }

    /** The person says what a column is, how the dates are written, or which way the amounts count. */
    fun map(change: (CsvMapping) -> CsvMapping) {
        val current = _state.value
        val table = current.table ?: return
        var mapping = change(current.mapping)
        var needsOrder = current.datesNeedOrder
        var unsure = current.orderUnsure
        if (mapping.date != current.mapping.date) {
            // Another column for the date: its own dates say how they are written, when they can.
            val dates = mapping.date?.let(table::column).orEmpty()
            val told = StatementText.dateOrder(dates, today())
            needsOrder = StatementText.needsOrder(dates)
            unsure = needsOrder && told == null
            if (told != null) mapping = mapping.copy(order = told)
        } else if (mapping.order != current.mapping.order) {
            unsure = false // the person said
        }
        _state.update { it.copy(mapping = mapping, datesNeedOrder = needsOrder, orderUnsure = unsure) }
        readJob?.cancel()
        readJob = viewModelScope.launch {
            val read = withContext(background) { CsvFile.read(table, mapping) }
            _state.update { if (it.mapping == mapping && it.table === table) it.copy(read = read) else it }
        }
    }

    /** From the columns to the lines. */
    fun review() {
        val current = _state.value
        if (!current.canContinue) return
        current.read?.let(::review)
    }

    private fun review(read: StatementRead) {
        val keys = ImportKeys.of(read.lines)
        _state.update {
            it.copy(
                step = ImportStep.Review, lines = read.lines.mapIndexed { index, line -> ReviewLine(line, keys[index]) },
                unread = read.unread, truncated = read.truncated, otherAccounts = read.otherAccounts, statementCurrency = read.currency, imported = 0, skipped = 0, sent = 0, toSend = 0,
            )
        }
        check()
    }

    /**
     * Sorts the lines: those the account already has, and for the others the category the person gave such a line
     * before. The server says what it holds (the account's keys, the person's texts with their categories) and the
     * comparison is made here: nothing of a line leaves the device before the person imports it.
     */
    fun check() {
        val accountId = _state.value.accountId ?: return
        _state.update { it.copy(isChecking = true, checkFailed = false, error = null, errorCode = null) }
        checkJob?.cancel()
        checkJob = viewModelScope.launch {
            try {
                val known = api.budget.importKnown(accountId)
                val already = known.importKeys.toHashSet()
                val suggested = HashMap<Pair<BudgetTransactionKind, String>, MutableList<String>>()
                known.categories.forEach { suggested.getOrPut(it.kind to it.note) { ArrayList() } += it.categoryId }
                _state.update { current ->
                    current.copy(
                        isChecking = false,
                        lines = current.lines.map { line ->
                            val there = line.key in already
                            // Only a category the person still has, of the line's kind: the answer is checked, not trusted.
                            val usable = current.categoriesFor(line.kind)
                            val category = suggested[line.kind to line.line.text]?.firstOrNull { id -> usable.any { it.id == id } }
                            line.copy(already = there, keep = !there, categoryId = category)
                        },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: KadansApiException) {
                _state.update { it.copy(isChecking = false, checkFailed = true, error = e.message, errorCode = e.errorCode) }
            } catch (_: Exception) {
                _state.update { it.copy(isChecking = false, checkFailed = true, error = null, errorCode = "network") }
            }
        }
    }

    fun keep(key: String, keep: Boolean) = _state.update { current ->
        current.copy(lines = current.lines.map { if (it.key == key && !it.already) it.copy(keep = keep) else it })
    }

    fun keepAll(keep: Boolean) = _state.update { current ->
        current.copy(lines = current.lines.map { if (it.already) it else it.copy(keep = keep) })
    }

    /** How many other lines read as the line named [key] does: the same shop, the same salary. */
    fun sameAs(key: String): Int {
        val lines = _state.value.lines
        val line = lines.firstOrNull { it.key == key } ?: return 0
        return lines.count { it.key != key && !it.already && it.kind == line.kind && it.line.text == line.line.text }
    }

    /** A line's category, and with [sameToo] that of every line that reads the same. */
    fun categorize(key: String, categoryId: String?, sameToo: Boolean) = _state.update { current ->
        val line = current.lines.firstOrNull { it.key == key } ?: return@update current
        current.copy(
            lines = current.lines.map {
                val same = sameToo && !it.already && it.kind == line.kind && it.line.text == line.line.text
                if (it.key == key || same) it.copy(categoryId = categoryId) else it
            },
        )
    }

    /**
     * Books the lines kept, [BudgetApi.IMPORT_AT_ONCE] at a time. A request that fails stops there: what the ones
     * before it booked is in the account, set aside here as "already there", and trying again sends only the rest.
     */
    fun import() {
        val start = _state.value
        val accountId = start.accountId ?: return
        if (!start.canImport) return
        val chosen = start.lines.filter { it.keep && !it.already }
        _state.update { it.copy(isImporting = true, sent = 0, toSend = chosen.size, error = null, errorCode = null) }
        viewModelScope.launch {
            try {
                for (part in chosen.chunked(BudgetApi.IMPORT_AT_ONCE)) {
                    val answer = api.budget.import(
                        ImportTransactions(
                            accountId,
                            part.map {
                                // Money moves on a day, not at a time: noon, as a movement recorded by hand.
                                ImportedMovement(it.kind, it.amount, it.line.date.atStartOfDayIn(timeZone) + 12.hours, it.key, it.line.text, it.categoryId)
                            },
                        ),
                    )
                    val booked = part.mapTo(HashSet()) { it.key }
                    _state.update { current ->
                        current.copy(
                            sent = current.sent + part.size,
                            imported = current.imported + answer.imported,
                            skipped = current.skipped + answer.skipped,
                            lines = current.lines.map { if (it.key in booked) it.copy(already = true, keep = false) else it },
                        )
                    }
                }
                _state.update { it.copy(isImporting = false, step = ImportStep.Done) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: KadansApiException) {
                _state.update { it.copy(isImporting = false, error = e.message, errorCode = e.errorCode) }
            } catch (_: Exception) {
                _state.update { it.copy(isImporting = false, error = null, errorCode = "network") }
            }
        }
    }

    /**
     * Back, one step at a time: from the lines to the columns (a CSV file) or to the file, from the columns to the
     * file. False where Back leaves the screen: at the first step, and at the last. While lines are being sent it does
     * nothing: leaving then would book half a statement.
     */
    fun back(): Boolean {
        val current = _state.value
        return when {
            current.isImporting -> true
            current.step == ImportStep.Review && current.table != null -> {
                checkJob?.cancel()
                _state.update { it.copy(step = ImportStep.Columns, lines = emptyList(), isChecking = false, checkFailed = false, error = null, errorCode = null) }
                true
            }
            current.step == ImportStep.Review || current.step == ImportStep.Columns -> {
                checkJob?.cancel()
                readJob?.cancel()
                _state.update {
                    it.copy(
                        step = ImportStep.Choose, table = null, read = null, lines = emptyList(), fileName = "", problem = null,
                        isChecking = false, checkFailed = false, error = null, errorCode = null,
                    )
                }
                true
            }
            else -> false
        }
    }
}

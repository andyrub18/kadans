package app.kadans.statement

import app.kadans.api.BudgetApi
import app.kadans.statement.StatementText.DateOrder
import kotlinx.datetime.LocalDate

/** One line of a statement, as Kadans books it if the person keeps it (ARCHITECTURE → "Statements in, data out"). */
data class StatementLine(
    val date: LocalDate,
    /** In hundredths, with its sign: above zero is money in (income), below is money out (an expense). Never zero. */
    val cents: Long,
    /** What the bank wrote on the line: the movement's note. */
    val text: String,
    /** The bank's own id for the line, when the file has one (OFX's FITID, a camt statement's reference). */
    val bankId: String? = null,
)

/**
 * What was read of a file. [unread]: its rows that are not a movement (no date, no amount, a total, an empty line).
 * [currency]: the currency the file says it is in, when it says (OFX), as its three letters in capitals.
 * [truncated]: the file holds more lines than are read at once ([CsvFile.MAX_ROWS]); the rest was left out, and the
 * person is told.
 * [otherAccounts]: the file holds statements of several accounts (camt); only the first account's lines were read.
 */
data class StatementRead(
    val lines: List<StatementLine>,
    val unread: Int = 0,
    val currency: String? = null,
    val truncated: Boolean = false,
    val otherAccounts: Boolean = false,
)

/** What kind of file the person picked, told from what is in it and never from its name. */
enum class StatementKind {
    Csv,
    Ofx,

    /** An ISO 20022 statement in XML: camt.053, or its relatives camt.052 and camt.054. */
    Camt,

    /** A PDF: a picture of a statement, not its lines. The bank's site usually offers CSV or OFX beside it. */
    Pdf,

    /** An Excel workbook: saved as CSV from Excel, it can be read. */
    Spreadsheet,

    /** Empty, too large, or nothing Kadans reads. */
    Unreadable,
}

object Statements {
    /** The kind of [bytes]: by its first bytes for the files that are not text, by its content for the others. */
    fun kind(bytes: ByteArray): StatementKind = when {
        bytes.isEmpty() || bytes.size > StatementText.MAX_BYTES -> StatementKind.Unreadable
        starts(bytes, 0x25, 0x50, 0x44, 0x46) -> StatementKind.Pdf // %PDF
        starts(bytes, 0x50, 0x4B, 0x03, 0x04) || starts(bytes, 0xD0, 0xCF, 0x11, 0xE0) -> StatementKind.Spreadsheet // .xlsx, .xls
        // Anything else that is not text (a picture, an archive): a statement has no zero byte, but for UTF-16.
        !utf16(bytes) && bytes.take(4096).any { it == 0.toByte() } -> StatementKind.Unreadable
        else -> StatementText.decode(bytes.copyOfRange(0, minOf(bytes.size, 4096))).let { start ->
            when {
                Ofx.looksLike(start) -> StatementKind.Ofx
                Camt.looksLike(start) -> StatementKind.Camt
                // Other markup is not a table of lines: some other XML, or a web page saved under a statement's name.
                // Read as CSV it would show its tags as columns.
                start.trimStart('\uFEFF', ' ', '\t', '\r', '\n').startsWith("<") -> StatementKind.Unreadable
                else -> StatementKind.Csv
            }
        }
    }

    private fun starts(bytes: ByteArray, vararg first: Int) = bytes.size >= first.size && first.indices.all { bytes[it] == first[it].toByte() }

    private fun utf16(bytes: ByteArray) = starts(bytes, 0xFF, 0xFE) || starts(bytes, 0xFE, 0xFF)

    /**
     * A line's text as a movement's note: one line, single spaces, nothing that is not text, cut to what a note holds.
     * It is the bank's text, and behind it a shop's or a sender's: never trusted to be harmless.
     */
    fun note(cell: String): String =
        cell.filter { !it.isISOControl() || it == ' ' || it == '\t' || it == '\n' }
            .replace(SPACES, " ")
            .trim()
            .let { if (it.length <= BudgetApi.NOTE_LENGTH) it else it.substring(0, if (it[BudgetApi.NOTE_LENGTH - 1].isHighSurrogate()) BudgetApi.NOTE_LENGTH - 1 else BudgetApi.NOTE_LENGTH) }

    private val SPACES = Regex("[\\s\\u00A0]+")

    /**
     * The bank's own id for a line, when it can be trusted to name that line for good. Some banks fill the field with
     * a word that says there is none ("NOTPROVIDED"), with zeros, or with the line's rank in the statement (1, 2, 3…,
     * starting again in the next one): such an id would make another statement's line look already booked, and that
     * line would be lost. A short id is therefore not believed; the line is then named by what it holds, as a CSV
     * file's is, which is never wrong about another line.
     */
    fun bankId(raw: String?): String? {
        val id = raw?.trim().orEmpty()
        if (id.length < MIN_ID || id.uppercase() in NO_ID || id.all { it == id[0] }) return null
        return id
    }

    /** Shorter than this, an id is more likely a rank than a name. */
    private const val MIN_ID = 6

    private val NO_ID = setOf("NOTPROVIDED", "NOT PROVIDED", "NONREF", "NOREF", "NONE", "NULL", "N/A", "UNKNOWN")
}

/**
 * The keys that name a statement's lines (the server's `Transaction.ImportKey`): the same line gets the same key every
 * time the file is read, and whenever the next statement holds it again, so an account books it once.
 * - With the bank's own id for the line (OFX, camt): made from that id. An id that two lines of the file share names
 *   neither of them (a bank that repeats one, or writes the same word on every line): those lines are named as the
 *   ones without an id are.
 * - Without: made from the line's day, amount and text, and its rank among the lines of the file that read exactly
 *   the same (two coffees at the same price on the same day are two lines, and both are booked).
 * No two lines of a file get the same key. A key is a number written in letters and digits, not the line: it says
 * nothing by itself.
 */
object ImportKeys {
    fun of(lines: List<StatementLine>): List<String> {
        val ids = lines.mapNotNull { Statements.bankId(it.bankId) }.groupingBy { it }.eachCount()
        val seen = HashMap<String, Int>()
        return lines.map { line ->
            val id = Statements.bankId(line.bankId)?.takeIf { ids[it] == 1 }
            if (id != null) {
                "b1-" + hash(id)
            } else {
                val same = "${line.date}|${line.cents}|${StatementText.plain(line.text)}"
                val rank = seen.getOrElse(same) { 0 }
                seen[same] = rank + 1
                "l1-" + hash("$same|$rank")
            }
        }
    }

    /** 128 bits in 32 hex digits: two FNV-1a sums with different starts. Not a secret, only a name that does not collide. */
    private fun hash(text: String): String {
        var a = 0xcbf29ce484222325uL
        var b = 0x84222325cbf29ce4uL
        for (byte in text.encodeToByteArray()) {
            val value = (byte.toInt() and 0xFF).toULong()
            a = (a xor value) * 0x100000001b3uL
            b = (b xor (value + 0x9EuL)) * 0x100000001b3uL
        }
        return a.toString(16).padStart(16, '0') + b.toString(16).padStart(16, '0')
    }
}

/** A CSV file as a table: its rows of cells, and its header when it has one. */
data class CsvTable(val header: List<String>?, val rows: List<List<String>>, val columns: Int, val unread: Int = 0, val truncated: Boolean = false) {
    fun column(index: Int): List<String> = rows.map { it.getOrElse(index) { "" } }
}

/**
 * Which column of a CSV file is what. Banks agree on nothing: the columns' names and order, how a date is written, and
 * whether money out is a negative amount or a column of its own. Kadans guesses ([CsvFile.guess]) and the person
 * corrects on one screen.
 */
data class CsvMapping(
    val date: Int? = null,
    val order: DateOrder = DateOrder.DayMonthYear,
    /** The line's text; null: none, the movements come without a note. */
    val text: Int? = null,
    /** One column for the amount, with its sign… */
    val amount: Int? = null,
    /** …or one for money out and one for money in. */
    val moneyOut: Int? = null,
    val moneyIn: Int? = null,
    /** One column: an amount above zero is money in. A credit card's statement has it the other way round. */
    val positiveIsIncome: Boolean = true,
) {
    val complete: Boolean get() = date != null && (amount != null || moneyOut != null || moneyIn != null)
}

object CsvFile {
    /** The most rows read of a file: several years of a busy account. */
    const val MAX_ROWS = 10_000

    /** The lines shown under the columns as the person chooses them: enough to see whether they are read right. */
    const val PREVIEW = 5

    /** The sign between cells: the one of `, ; tab |` that cuts the first lines into the same number of cells. */
    fun delimiter(text: String): Char {
        val sample = text.lineSequence().filter { it.isNotBlank() }.take(30).joinToString("\n")
        return listOf(',', ';', '\t', '|').maxByOrNull { sign ->
            val counts = cells(sample, sign).map { it.size }.filter { it > 1 }
            val usual = counts.groupingBy { it }.eachCount().maxByOrNull { it.value } ?: return@maxByOrNull 0
            usual.value * 100 + usual.key
        } ?: ','
    }

    /** The file's rows of cells (RFC 4180: quotes around a cell that holds the sign, a quote or a line break). */
    fun cells(text: String, delimiter: Char): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        fun endCell() {
            row.add(cell.toString().trim())
            cell.clear()
        }
        fun endRow() {
            endCell()
            if (row.any { it.isNotEmpty() }) rows.add(row)
            row = ArrayList()
        }
        // One row past the most that is kept: it tells a file that has more from one that ends there.
        while (i < text.length && rows.size <= MAX_ROWS) {
            val c = text[i]
            when {
                quoted && c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { cell.append('"'); i++ }
                c == '"' && (quoted || cell.isBlank()) -> { if (!quoted) cell.clear(); quoted = !quoted }
                !quoted && c == delimiter -> endCell()
                !quoted && (c == '\n' || c == '\r') -> { if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++; endRow() }
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) endRow()
        return rows
    }

    /**
     * The table in the file. A bank often writes a few lines about the account first and a total last: the table is
     * the rows of one width, the width whose rows most often hold both a date and an amount (and, when none does, the
     * most common width). The other lines are counted as unread, never made into movements. The first row of the
     * table is its header when it holds neither a date nor an amount.
     */
    fun table(text: String): CsvTable {
        val read = cells(text, delimiter(text))
        val truncated = read.size > MAX_ROWS
        val all = if (truncated) read.subList(0, MAX_ROWS) else read
        val byWidth = all.filter { it.size > 1 }.groupBy { it.size }
        val usual = byWidth.entries.maxWithOrNull(
            compareBy({ (_, rows) -> rows.take(SAMPLE).count(::looksLikeMovement) }, { it.value.size }, { it.key }),
        )?.key ?: return CsvTable(null, emptyList(), 0, unread = all.size)
        val rows = all.filter { it.size == usual }
        val first = rows.firstOrNull() ?: return CsvTable(null, emptyList(), usual, unread = all.size)
        val isHeader = first.none { looksLikeDate(it) || StatementText.cents(it) != null } && first.any { cell -> cell.any { it.isLetter() } }
        return CsvTable(if (isHeader) first else null, if (isHeader) rows.drop(1) else rows, usual, unread = all.size - rows.size, truncated = truncated)
    }

    /** How many rows are looked at to tell what a column holds. */
    private const val SAMPLE = 200

    private fun looksLikeMovement(row: List<String>) = row.any(::looksLikeDate) && row.any { StatementText.cents(it) != null }

    private fun looksLikeDate(cell: String) = DateOrder.entries.any { StatementText.date(cell, it) != null }

    /** An amount written as money is: with decimals, a sign, brackets or CR/DR. A bare whole number may be a reference. */
    private fun looksLikeMoney(cell: String): Boolean =
        StatementText.cents(cell) != null && (MONEY_SHAPE.containsMatchIn(cell) || cell.any { it in "-+()\u2212" } || cell.trim().uppercase().let { it.endsWith("CR") || it.endsWith("DR") })

    private val MONEY_SHAPE = Regex("[.,]\\d{1,2}\\D*$")

    /**
     * The best guess at what each column is. The person sees it with the first lines read that way, and corrects it.
     *
     * A column's name proposes and what it holds confirms: a name is believed only when most of the column's cells
     * are of the kind the name says ("Date valeur" holds dates, so it is never the amount, whatever "valeur" means
     * elsewhere). Names are tried in the order of their lists, the surest first, in English, French, Spanish and
     * Kreyòl. Where no name helps (no header, or one in other words), the cells alone decide:
     * - the date is the column where most cells read as dates;
     * - two columns of amounts of which each row fills one or the other are money out and money in, in that order
     *   (banks put the debit column before the credit column);
     * - else the amount is the column whose cells most look like money, the first of them: a balance comes after;
     * - the text is the column with the most letters among those left.
     */
    fun guess(table: CsvTable, fallbackOrder: DateOrder = DateOrder.DayMonthYear, today: LocalDate? = null): CsvMapping {
        val names = table.header?.map { StatementText.plain(it) }
        val sample = table.rows.take(SAMPLE)
        val columns = 0 until table.columns
        fun filled(index: Int) = sample.map { it.getOrElse(index) { "" } }.filter { it.isNotBlank() }
        fun share(index: Int, test: (String) -> Boolean): Double = filled(index).let { if (it.isEmpty()) 0.0 else it.count(test).toDouble() / it.size }
        val isAmount = { cell: String -> StatementText.cents(cell) != null }
        val isText = { cell: String -> cell.any(Char::isLetter) && !looksLikeDate(cell) && StatementText.cents(cell) == null }

        // By name, confirmed by content (an empty column cannot contradict its name).
        fun named(words: List<String>, taken: Set<Int>, test: (String) -> Boolean): Int? {
            if (names == null) return null
            for (word in words) {
                val match = columns.firstOrNull { index ->
                    val name = names.getOrElse(index) { "" }
                    index !in taken &&
                        (name == word || name.startsWith("$word ") || name.endsWith(" $word") || word.length > 5 && word in name) &&
                        (filled(index).isEmpty() || share(index, test) >= CONFIRMS)
                }
                if (match != null) return match
            }
            return null
        }

        // The day a movement was made, before the day the bank counts it from (its "value date"), wherever each stands.
        val valueDates = columns.filter { index -> names?.getOrNull(index)?.let { "valeur" in it || "value" in it } == true }.toSet()
        val date = named(DATE, valueDates, ::looksLikeDate) ?: named(DATE, emptySet(), ::looksLikeDate)
            ?: columns.maxByOrNull { share(it, ::looksLikeDate) }?.takeIf { share(it, ::looksLikeDate) >= MOSTLY }
        val order = date?.let { StatementText.dateOrder(table.column(it), today) } ?: fallbackOrder
        val balance = named(BALANCE, setOfNotNull(date), isAmount)
        val taken = setOfNotNull(date, balance)

        var moneyOut = named(MONEY_OUT, taken, isAmount)
        var moneyIn = named(MONEY_IN, taken + setOfNotNull(moneyOut), isAmount)
        var amount = if (moneyOut != null || moneyIn != null) null else named(AMOUNT, taken, isAmount)
        val numeric = columns.filter { it !in taken && share(it, isAmount) >= MOSTLY }
        // One side named and the other in words not known here: its partner is the column that shares the rows with it.
        if (moneyOut != null && moneyIn == null) moneyIn = numeric.firstOrNull { it != moneyOut && eitherOr(sample, moneyOut!!, it) }
        if (moneyIn != null && moneyOut == null) moneyOut = numeric.firstOrNull { it != moneyIn && eitherOr(sample, it, moneyIn!!) }
        if (moneyOut == null && moneyIn == null && amount == null) {
            // No name to go by. Two columns that share the rows between them are money out and money in.
            val pair = numeric.zipWithNext().firstOrNull { (a, b) -> eitherOr(sample, a, b) }
            if (pair != null) {
                moneyOut = pair.first
                moneyIn = pair.second
            } else {
                amount = numeric.maxByOrNull { share(it, ::looksLikeMoney) }
            }
        }
        val used = taken + setOfNotNull(moneyOut, moneyIn, amount)
        val text = named(TEXT, used, isText) ?: columns.filter { it !in used && it !in numeric }
            .maxByOrNull { index -> sample.sumOf { it.getOrElse(index) { "" }.count(Char::isLetter) } }
            ?.takeIf { index -> sample.any { row -> row.getOrElse(index) { "" }.any(Char::isLetter) } }
        return CsvMapping(date, order, text, amount, moneyOut, moneyIn)
    }

    /** A name is believed when at least this share of its column's cells are what it says. */
    private const val CONFIRMS = 0.5

    /** Without a name, a column is a date or an amount when at least this share of its cells read so. */
    private const val MOSTLY = 0.6

    /**
     * Whether columns [a] and [b] share the rows between them: nearly every row fills one and leaves the other empty
     * (or at zero), and each of them is filled somewhere. A debit column and its credit column do; an amount and the
     * balance after it do not.
     */
    private fun eitherOr(sample: List<List<String>>, a: Int, b: Int): Boolean {
        fun moved(row: List<String>, index: Int) = StatementText.cents(row.getOrElse(index) { "" }).let { it != null && it != 0L }
        val rows = sample.filter { moved(it, a) || moved(it, b) }
        if (rows.size < 2) return false
        val onlyA = rows.count { moved(it, a) && !moved(it, b) }
        val onlyB = rows.count { moved(it, b) && !moved(it, a) }
        return onlyA > 0 && onlyB > 0 && (onlyA + onlyB) >= rows.size * 0.9
    }

    /** The table's lines read through [mapping]. A row without a date or an amount, or with nothing moved, is unread. */
    fun read(table: CsvTable, mapping: CsvMapping): StatementRead {
        val dateColumn = mapping.date ?: return StatementRead(emptyList(), table.unread + table.rows.size, truncated = table.truncated)
        val lines = ArrayList<StatementLine>(table.rows.size)
        var unread = table.unread
        for (row in table.rows) {
            val date = StatementText.date(row.getOrElse(dateColumn) { "" }, mapping.order)
            val cents = when {
                mapping.amount != null -> StatementText.cents(row.getOrElse(mapping.amount) { "" })?.let { if (mapping.positiveIsIncome) it else -it }
                else -> {
                    val out = mapping.moneyOut?.let { StatementText.cents(row.getOrElse(it) { "" }) }
                    val came = mapping.moneyIn?.let { StatementText.cents(row.getOrElse(it) { "" }) }
                    if (out == null && came == null) null else kotlin.math.abs(came ?: 0) - kotlin.math.abs(out ?: 0)
                }
            }
            if (date == null || cents == null || cents == 0L) {
                unread++
                continue
            }
            lines += StatementLine(date, cents, mapping.text?.let { Statements.note(row.getOrElse(it) { "" }) }.orEmpty())
        }
        return StatementRead(lines, unread, truncated = table.truncated)
    }

    // A column's possible names, without accents, the surest first: a statement's own date before its value date, a
    // description before a reference.
    private val DATE = listOf("date", "dat", "date operation", "date de l'operation", "transaction date", "posting date", "posted", "fecha", "value date", "date valeur")
    private val TEXT = listOf("description", "deskripsyon", "libelle", "libele", "details", "detail", "detay", "narrative", "designation", "motif", "concepto", "payee", "label", "memo", "transaction", "reference")
    private val AMOUNT = listOf("amount", "montant", "montan", "importe", "monto", "sum", "total", "value", "valeur")
    private val MONEY_OUT = listOf("debit", "debi", "withdrawal", "withdrawals", "retrait", "retraits", "retre", "paid out", "money out", "sortie", "sorties", "cargo", "debito")
    private val MONEY_IN = listOf("credit", "kredi", "deposit", "deposits", "depot", "depots", "depo", "paid in", "money in", "entree", "entrees", "abono", "credito")
    private val BALANCE = listOf("balance", "balans", "solde", "sold", "saldo", "running balance")
}

/**
 * An OFX or QFX file (Quicken, Money and most banks' "download for accounting software"): each movement in a
 * `STMTTRN` block, with the bank's own id for it. Read by its tags without minding whether they are closed: version 1
 * is SGML and closes none, version 2 is XML and closes all.
 */
object Ofx {
    fun looksLike(start: String): Boolean = start.uppercase().let { "<OFX" in it || "OFXHEADER" in it }

    fun read(text: String): StatementRead {
        val lines = ArrayList<StatementLine>()
        var unread = 0
        // One block past the most that is kept: it tells a file that has more from one that ends there.
        val blocks = BLOCK.findAll(text).take(CsvFile.MAX_ROWS + 1).toList()
        for (block in blocks.take(CsvFile.MAX_ROWS)) {
            val body = block.groupValues[1]
            val date = tag(body, DTPOSTED)?.takeIf { it.length >= 8 && it.take(8).all(Char::isDigit) }
                ?.let { runCatching { LocalDate(it.substring(0, 4).toInt(), it.substring(4, 6).toInt(), it.substring(6, 8).toInt()) }.getOrNull() }
                ?.takeIf { it.year in 1990..2100 }
            // As the standard writes it (no sign between thousands); a bank that adds one is still read.
            val cents = tag(body, TRNAMT)?.let { StatementText.decimalCents(it) ?: StatementText.cents(it) }
            if (date == null || cents == null || cents == 0L) {
                unread++
                continue
            }
            val name = tag(body, NAME).orEmpty()
            val memo = tag(body, MEMO).orEmpty()
            val words = when {
                memo.isBlank() || memo.equals(name, ignoreCase = true) -> name
                name.isBlank() -> memo
                else -> "$name – $memo"
            }
            lines += StatementLine(date, cents, Statements.note(words), Statements.bankId(tag(body, FITID)))
        }
        val currency = tag(text, CURDEF)?.uppercase()?.takeIf { it.length == 3 && it.all(Char::isLetter) }
        return StatementRead(lines, unread, currency, truncated = blocks.size > CsvFile.MAX_ROWS)
    }

    /** A tag's value: up to the next tag or the end of the line, with the few entities OFX uses turned back into signs. */
    private fun tag(body: String, tag: Regex): String? =
        tag.find(body)?.groupValues?.get(1)?.trim()
            ?.replace("&lt;", "<")?.replace("&gt;", ">")?.replace("&apos;", "'")?.replace("&quot;", "\"")?.replace("&amp;", "&")

    private fun valueOf(name: String) = Regex("<$name>([^<\\r\\n]*)", RegexOption.IGNORE_CASE)

    private val DTPOSTED = valueOf("DTPOSTED")
    private val TRNAMT = valueOf("TRNAMT")
    private val NAME = valueOf("NAME")
    private val MEMO = valueOf("MEMO")
    private val FITID = valueOf("FITID")
    private val CURDEF = valueOf("CURDEF")

    // "[\\s\\S]" below: any sign, a line break too (the option that lets a point match one is not in every Kotlin target).
    private val BLOCK = Regex("<STMTTRN>([\\s\\S]*?)(?=</STMTTRN>|<STMTTRN>|</BANKTRANLIST>|$(?![\\s\\S]))", RegexOption.IGNORE_CASE)
}

/**
 * An ISO 20022 statement (camt.053; the day's report camt.052 and the notification camt.054 read the same way), the
 * international standard a bank's "CAMT" export follows. Each movement is an `Ntry`: its amount with its currency,
 * whether it is a credit or a debit (`CdtDbtInd`), the day it was booked, and the bank's own reference for it. Nothing
 * to guess, and nothing to ask the person.
 *
 * Read by its tags, as OFX is, with a namespace's prefix allowed for (`<ns2:Ntry>`). No XML machinery is run on a file
 * from outside: nothing in it is expanded, included or fetched, whatever it declares.
 * - Only what is booked: an entry marked pending, for information or to come is left out.
 * - An entry that gathers several transactions (a batch) is one line, with the entry's own amount: that is what the
 *   account's balance moved by.
 * - A file with the statements of several accounts gives the first account's lines only, and says so: the others
 *   belong on other accounts.
 */
object Camt {
    fun looksLike(start: String): Boolean = "camt.05" in start || "BkToCstmr" in start

    fun read(text: String): StatementRead {
        // A value written as it is (CDATA) becomes an ordinary value: its signs escaped, like any other.
        val xml = CDATA.replace(text) { it.groupValues[1].replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") }
        val statements = listOf(STMT, RPT, NTFCTN).firstNotNullOfOrNull { tag -> tag.findAll(xml).map { it.groupValues[1] }.toList().ifEmpty { null } } ?: listOf(xml)
        val firstAccount = accountOf(statements.first())
        val mine = statements.filter { accountOf(it) == firstAccount }

        val lines = ArrayList<StatementLine>()
        var unread = 0
        var truncated = false
        var currency = mine.firstNotNullOfOrNull { statement -> ACCT.find(statement)?.groupValues?.get(1)?.let { value(it, CCY) } }
        for (entry in mine.asSequence().flatMap { statement -> NTRY.findAll(statement).map { it.groupValues[1] } }) {
            if (lines.size + unread >= CsvFile.MAX_ROWS) {
                truncated = true
                break
            }
            // The entry's own fields come before its details, which repeat some of their names.
            val head = entry.substringBefore("NtryDtls>")
            val amount = AMT.find(head)
            val cents = amount?.groupValues?.get(2)?.let(StatementText::decimalCents)
            val direction = value(head, CDT_DBT_IND)?.uppercase()
            val status = STS.find(head)?.groupValues?.get(1)?.let { value(it, CD) ?: it.trim() }?.uppercase()
            val date = (BOOKG_DT.find(head) ?: VAL_DT.find(head))?.groupValues?.get(1)?.let { value(it, DT) ?: value(it, DT_TM) }?.let(::day)
            val signed = when (direction) {
                "CRDT" -> cents
                "DBIT" -> cents?.let { -it }
                else -> null
            }
            if (date == null || signed == null || signed == 0L || status in NOT_BOOKED) {
                unread++
                continue
            }
            if (currency == null) currency = amount?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }
            val details = TX_DTLS.findAll(entry).map { it.groupValues[1] }.toList()
            val about = value(entry, ADDTL_NTRY_INF).orEmpty()
            val words = when (details.size) {
                1 -> wordsOf(details[0], debit = direction == "DBIT").ifBlank { about }
                else -> about.ifBlank { details.firstOrNull()?.let { wordsOf(it, debit = direction == "DBIT") }.orEmpty() }
            }
            val reference = value(head, ACCT_SVCR_REF) ?: details.singleOrNull()?.let { detail -> REFS.find(detail)?.groupValues?.get(1)?.let { value(it, ACCT_SVCR_REF) } }
            lines += StatementLine(date, signed, Statements.note(words), Statements.bankId(reference))
        }
        return StatementRead(lines, unread, currency?.uppercase()?.takeIf { it.length == 3 && it.all(Char::isLetter) }, truncated, otherAccounts = mine.size < statements.size)
    }

    /** What names a statement's account (its IBAN, or the bank's own number for it), or null when it does not say. */
    private fun accountOf(statement: String): String? =
        ACCT.find(statement)?.groupValues?.get(1)?.let { ID.find(it)?.groupValues?.get(1) }?.replace(ANY_TAG, "")?.filterNot { it.isWhitespace() }?.takeIf { it.isNotEmpty() }

    /** A transaction in words a person knows it by: who was paid (or who paid), and what the payment said. */
    private fun wordsOf(detail: String, debit: Boolean): String {
        val party = RLTD_PTIES.find(detail)?.groupValues?.get(1)?.let { parties -> (if (debit) CDTR else DBTR).find(parties)?.groupValues?.get(1) }?.let { value(it, NM) }.orEmpty()
        val said = RMT_INF.find(detail)?.groupValues?.get(1)?.let { info -> USTRD.findAll(info).joinToString(" ") { unescape(it.groupValues[1]).trim() } }.orEmpty()
        return listOf(party, said).filter { it.isNotBlank() }.distinct().joinToString(" – ").ifBlank { value(detail, ADDTL_TX_INF).orEmpty() }
    }

    /** "2026-11-03", or a moment of that day. */
    private fun day(text: String): LocalDate? {
        val match = ISO_DAY.find(text.trim()) ?: return null
        val (year, month, date) = match.destructured
        return runCatching { LocalDate(year.toInt(), month.toInt(), date.toInt()) }.getOrNull()?.takeIf { it.year in 1990..2100 }
    }

    private fun value(xml: String, tag: Regex): String? = tag.find(xml)?.groupValues?.get(1)?.let(::unescape)?.trim()?.takeIf { it.isNotEmpty() }

    /** The five signs XML escapes, and a sign written by its number. `&amp;` last, so that nothing is unescaped twice. */
    private fun unescape(text: String): String {
        if ('&' !in text) return text
        return NUMBERED.replace(text) { match ->
            val code = match.groupValues[1].let { if (it.startsWith("x") || it.startsWith("X")) it.drop(1).toIntOrNull(16) else it.toIntOrNull() }
            // A sign of the first plane that is one; anything else is left as it was written.
            if (code != null && code in 0x20..0xFFFD && code !in 0xD800..0xDFFF) code.toChar().toString() else match.value
        }.replace("&lt;", "<").replace("&gt;", ">").replace("&apos;", "'").replace("&quot;", "\"").replace("&amp;", "&")
    }

    // A tag by its name, with or without a namespace's prefix and attributes: its content, or its text.
    private fun block(name: String) = Regex("<(?:[\\w.-]+:)?$name(?:\\s[^>]*)?>([\\s\\S]*?)</(?:[\\w.-]+:)?$name\\s*>")

    private fun text(name: String) = Regex("<(?:[\\w.-]+:)?$name(?:\\s[^>]*)?>([^<]*)</")

    private val STMT = block("Stmt")
    private val RPT = block("Rpt")
    private val NTFCTN = block("Ntfctn")
    private val ACCT = block("Acct")
    private val ID = block("Id")
    private val NTRY = block("Ntry")
    private val STS = block("Sts")
    private val BOOKG_DT = block("BookgDt")
    private val VAL_DT = block("ValDt")
    private val TX_DTLS = block("TxDtls")
    private val REFS = block("Refs")
    private val RLTD_PTIES = block("RltdPties")
    private val CDTR = block("Cdtr")
    private val DBTR = block("Dbtr")
    private val RMT_INF = block("RmtInf")
    private val CCY = text("Ccy")
    private val CDT_DBT_IND = text("CdtDbtInd")
    private val CD = text("Cd")
    private val DT = text("Dt")
    private val DT_TM = text("DtTm")
    private val ACCT_SVCR_REF = text("AcctSvcrRef")
    private val NM = text("Nm")
    private val USTRD = text("Ustrd")
    private val ADDTL_NTRY_INF = text("AddtlNtryInf")
    private val ADDTL_TX_INF = text("AddtlTxInf")

    /** The amount with its currency: `<Amt Ccy="EUR">42.17</Amt>`. */
    private val AMT = Regex("<(?:[\\w.-]+:)?Amt(?:\\s+[^>]*?Ccy\\s*=\\s*[\"']([A-Za-z]{3})[\"'][^>]*|\\s[^>]*)?>([^<]*)</")
    private val CDATA = Regex("<!\\[CDATA\\[([\\s\\S]*?)]]>")
    private val ANY_TAG = Regex("<[^>]*>")
    private val NUMBERED = Regex("&#([xX]?[0-9A-Fa-f]+);")
    private val ISO_DAY = Regex("^(\\d{4})-(\\d{2})-(\\d{2})")

    /** Pending, for information only, or yet to come: not on the account. */
    private val NOT_BOOKED = setOf("PDNG", "INFO", "FUTR")
}

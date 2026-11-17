package app.kadans.statement

import kotlinx.datetime.LocalDate

/**
 * A bank's file as text, and its cells as dates and amounts (ARCHITECTURE → "Statements in, data out"). Pure: a
 * statement is read on the person's device, by this code, and never sent anywhere as it is. Nothing here trusts the
 * file: it is somebody else's, whatever its name says.
 */
object StatementText {
    /** The largest file read at all: a year of a busy account is a few hundred kilobytes. */
    const val MAX_BYTES = 5 * 1024 * 1024

    /**
     * The file's text. Banks export UTF-8 (with or without its mark), UTF-16 (Excel's "Unicode text"), or the old
     * Windows encoding of Western Europe, where "é" is one byte: read as that when the bytes are not valid UTF-8.
     */
    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return utf16(bytes, 2, littleEndian = true)
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return utf16(bytes, 2, littleEndian = false)
        val start = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) 3 else 0
        return try {
            bytes.decodeToString(start, bytes.size, throwOnInvalidSequence = true)
        } catch (_: Exception) {
            buildString(bytes.size) { for (i in start until bytes.size) append(windows1252(bytes[i].toInt() and 0xFF)) }
        }
    }

    private fun utf16(bytes: ByteArray, from: Int, littleEndian: Boolean): String = buildString((bytes.size - from) / 2) {
        var i = from
        while (i + 1 < bytes.size) {
            val a = bytes[i].toInt() and 0xFF
            val b = bytes[i + 1].toInt() and 0xFF
            append((if (littleEndian) (b shl 8) or a else (a shl 8) or b).toChar())
            i += 2
        }
    }

    /** Windows-1252: Latin-1, but for the 32 codes where it has "€", curly quotes and dashes. */
    private fun windows1252(code: Int): Char = if (code in 0x80..0x9F) WINDOWS_1252[code - 0x80] else code.toChar()

    private const val WINDOWS_1252 = "\u20AC\uFFFD\u201A\u0192\u201E\u2026\u2020\u2021\u02C6\u2030\u0160\u2039\u0152\uFFFD\u017D\uFFFD" +
        "\uFFFD\u2018\u2019\u201C\u201D\u2022\u2013\u2014\u02DC\u2122\u0161\u203A\u0153\uFFFD\u017E\u0178"

    /**
     * An amount in hundredths ("1,250.50" is 125050), with its sign; null when the cell holds no amount. Read as banks
     * write them: a currency's sign or letters around it, spaces or a sign between thousands, a comma or a point for
     * the decimals, a minus in front or behind, brackets for a negative amount, "CR" and "DR".
     */
    fun cents(cell: String): Long? {
        var text = cell.trim()
        if (text.isEmpty()) return null
        var negative = false
        val upper = text.uppercase()
        if (upper.endsWith("DR") || upper.endsWith("DB")) negative = true
        if (text.startsWith("(") && text.endsWith(")")) negative = true
        // A date or an hour is not an amount ("2026-11-16", "14:30"), nor is a range or a reference ("12-34").
        if (':' in text || NOT_ONE_NUMBER.containsMatchIn(text)) return null
        // A minus outside the digits: in front, behind, or after the currency ("$-12.00"). Unicode's too.
        if (text.any { it == '-' || it == '\u2212' || it == '\u2013' }) negative = true
        val digits = text.filter { it.isDigit() || it == '.' || it == ',' }
        if (digits.none { it.isDigit() }) return null
        // No letters inside the number itself: "12 Nov 2026" is not an amount.
        if (LETTERS_INSIDE.containsMatchIn(text)) return null

        val lastPoint = digits.lastIndexOf('.')
        val lastComma = digits.lastIndexOf(',')
        val decimalAt = when {
            lastPoint >= 0 && lastComma >= 0 -> maxOf(lastPoint, lastComma)
            lastPoint >= 0 -> lastPoint.takeIf { decimal(digits, it, '.') }
            lastComma >= 0 -> lastComma.takeIf { decimal(digits, it, ',') }
            else -> null
        } ?: -1
        val whole = (if (decimalAt >= 0) digits.substring(0, decimalAt) else digits).filter { it.isDigit() }
        var fraction = if (decimalAt >= 0) digits.substring(decimalAt + 1).filter { it.isDigit() } else ""
        if (whole.length > 13) return null // beyond any account
        // Hundredths: a third decimal is rounded away, half up, as a bank would print it.
        val roundUp = fraction.length > 2 && fraction[2] >= '5'
        fraction = fraction.take(2).padEnd(2, '0')
        val value = (whole.ifEmpty { "0" }.toLong()) * 100 + fraction.toLong() + if (roundUp) 1 else 0
        if (value > MAX_CENTS) return null
        return if (negative) -value else value
    }

    /** The most a movement holds (the server's `Money.ValidateAmount`): a trillion, in hundredths. */
    const val MAX_CENTS = 100_000_000_000_000L

    /**
     * An amount as a standard writes it (OFX, ISO 20022): digits, one point (or comma) for the decimals, a sign in
     * front, and never a sign between thousands. So "1.250" is one and a quarter here, where a bank's own CSV would
     * mean 1,250 ([cents]). Null when the text is not written so.
     */
    fun decimalCents(text: String): Long? {
        val match = STANDARD_AMOUNT.matchEntire(text.trim()) ?: return null
        val (sign, whole, fraction) = match.destructured
        if (whole.isEmpty() && fraction.isEmpty() || whole.length > 13) return null
        val roundUp = fraction.length > 2 && fraction[2] >= '5'
        val value = whole.ifEmpty { "0" }.toLong() * 100 + fraction.take(2).padEnd(2, '0').toLong() + if (roundUp) 1 else 0
        if (value > MAX_CENTS) return null
        return if (sign == "-") -value else value
    }

    private val STANDARD_AMOUNT = Regex("([+-]?)(\\d*)(?:[.,](\\d*))?")

    /**
     * Whether the only separator of its kind is the decimal one: it is when one or two digits follow it ("12,5",
     * "1250.00"), and it is the sign between thousands when exactly three do or when there are several ("1,250",
     * "1.250.000").
     */
    private fun decimal(digits: String, at: Int, sign: Char): Boolean {
        if (digits.count { it == sign } > 1) return false
        val after = digits.length - at - 1
        return after != 3
    }

    private val LETTERS_INSIDE = Regex("\\d\\s*\\p{L}+\\s*\\d")
    private val NOT_ONE_NUMBER = Regex("\\d\\s*[-/\u2212\u2013]\\s*\\d")

    /** The order of a date's parts when it is written in numbers. */
    enum class DateOrder { DayMonthYear, MonthDayYear, YearMonthDay }

    /**
     * A date written as banks write them: "16/11/2026", "11/16/26", "2026-11-16", "16.11.2026", "16-Nov-2026",
     * "Nov 16, 2026", "16 novembre 2026", with a time after it or not. [order] says how to read the numbers when the
     * year is not in front; null when the cell is not a date, or not a day that exists.
     */
    fun date(cell: String, order: DateOrder): LocalDate? {
        val text = cell.trim().substringBefore('T').trim()
        named(text)?.let { return it }
        val match = NUMERIC_DATE.find(text) ?: return null
        val (a, b, c) = match.destructured
        val parts = when {
            a.length == 4 -> Triple(a.toInt(), b.toInt(), c.toInt())
            order == DateOrder.MonthDayYear -> Triple(year(c), a.toInt(), b.toInt())
            order == DateOrder.YearMonthDay && c.length <= 2 && a.length == 2 -> Triple(year(a), b.toInt(), c.toInt())
            else -> Triple(year(c), b.toInt(), a.toInt())
        }
        return valid(parts.first, parts.second, parts.third)
    }

    /**
     * How the numbers of these dates are ordered, when they say so themselves: a first part above 12 is a day, a second
     * part above 12 is a day too. When every date is before the 13th, [today] can still tell: a statement holds what
     * happened, so the order that puts a line in the future is the wrong one. Null when nothing tells: the person is
     * asked.
     */
    fun dateOrder(cells: List<String>, today: LocalDate? = null): DateOrder? {
        var dayFirst = false
        var monthFirst = false
        var numeric = false
        for (cell in cells) {
            val match = NUMERIC_DATE.find(cell.trim().substringBefore('T').trim()) ?: continue
            val (a, b, _) = match.destructured
            if (a.length == 4) return DateOrder.YearMonthDay
            numeric = true
            if (a.toInt() > 12) dayFirst = true
            if (b.toInt() > 12) monthFirst = true
        }
        return when {
            dayFirst && !monthFirst -> DateOrder.DayMonthYear
            monthFirst && !dayFirst -> DateOrder.MonthDayYear
            dayFirst || !numeric || today == null -> null
            else -> {
                fun ahead(order: DateOrder) = cells.any { cell -> date(cell, order)?.let { it > today } == true }
                val dayFirstAhead = ahead(DateOrder.DayMonthYear)
                val monthFirstAhead = ahead(DateOrder.MonthDayYear)
                when {
                    dayFirstAhead && !monthFirstAhead -> DateOrder.MonthDayYear
                    monthFirstAhead && !dayFirstAhead -> DateOrder.DayMonthYear
                    else -> null
                }
            }
        }
    }

    /** Whether these dates are written in numbers with the year behind: only then is there an order to say. */
    fun needsOrder(cells: List<String>): Boolean = cells.any { cell ->
        NUMERIC_DATE.find(cell.trim().substringBefore('T').trim())?.let { it.groupValues[1].length != 4 } == true
    }

    private fun year(text: String): Int = text.toInt().let { if (text.length <= 2) 2000 + it else it }

    private fun valid(year: Int, month: Int, day: Int): LocalDate? =
        if (year !in 1990..2100 || month !in 1..12 || day !in 1..31) null else runCatching { LocalDate(year, month, day) }.getOrNull()

    /** "16-Nov-2026", "16 novembre 2026", "Nov 16, 2026": the month in English or French letters. */
    private fun named(text: String): LocalDate? {
        val dayFirst = DAY_MONTH_NAME.find(text)
        if (dayFirst != null) {
            val (day, month, year) = dayFirst.destructured
            return month(month)?.let { valid(year(year), it, day.toInt()) }
        }
        val monthFirst = MONTH_NAME_DAY.find(text) ?: return null
        val (month, day, year) = monthFirst.destructured
        return month(month)?.let { valid(year(year), it, day.toInt()) }
    }

    /** A month by its name or its short form, English or French: "juin" and "juil." differ at their fourth letter. */
    private fun month(name: String): Int? {
        val plain = plain(name)
        return when {
            plain.startsWith("juin") -> 6
            plain.startsWith("juil") -> 7
            else -> MONTHS[plain.take(3)]
        }
    }

    /** Lower case without accents: "Débit" and "debit" are the same word, "févr." and "fev" the same month. */
    fun plain(text: String): String = buildString(text.length) {
        for (c in text.lowercase()) append(ACCENTS[c] ?: c)
    }

    private val ACCENTS = mapOf(
        'à' to 'a', 'â' to 'a', 'ä' to 'a', 'á' to 'a', 'ç' to 'c', 'é' to 'e', 'è' to 'e', 'ê' to 'e', 'ë' to 'e',
        'í' to 'i', 'î' to 'i', 'ï' to 'i', 'ñ' to 'n', 'ó' to 'o', 'ò' to 'o', 'ô' to 'o', 'ö' to 'o', 'ú' to 'u', 'ù' to 'u', 'û' to 'u', 'ü' to 'u',
    )

    private val NUMERIC_DATE = Regex("^(\\d{1,4})[/.\\-](\\d{1,2})[/.\\-](\\d{1,4})(?:[ ,].*)?$")
    private val DAY_MONTH_NAME = Regex("^(\\d{1,2})[ \\-/.]+([\\p{L}]{3,9})\\.?[ \\-/.,]+(\\d{2,4})(?:[ ,].*)?$")
    private val MONTH_NAME_DAY = Regex("^([\\p{L}]{3,9})\\.?[ \\-/.]+(\\d{1,2}),?[ \\-/.]+(\\d{2,4})(?:[ ,].*)?$")

    /** By their first three letters, without accents: English and French share most. */
    private val MONTHS = mapOf(
        "jan" to 1, "feb" to 2, "fev" to 2, "mar" to 3, "apr" to 4, "avr" to 4, "may" to 5, "mai" to 5, "jun" to 6,
        "jul" to 7, "aug" to 8, "aou" to 8, "sep" to 9, "oct" to 10, "nov" to 11, "dec" to 12,
    )
}

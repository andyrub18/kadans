package app.kadans.ui.budget

import app.kadans.api.model.Currency
import kotlin.math.abs
import kotlin.math.roundToLong

/** Groups thousands and joins an amount to its currency: a no-break space, so "2 500.00 USD" never wraps inside. */
internal const val MONEY_SPACE = '\u00A0'

/** "85 000.50" — space-grouped thousands (the local convention), always two decimals. */
internal fun formatAmount(value: Double): String {
    val cents = (abs(value) * 100).roundToLong()
    val whole = cents / 100
    val fraction = (cents % 100).toString().padStart(2, '0')
    val grouped = whole.toString().reversed().chunked(3).joinToString(MONEY_SPACE.toString()).reversed()
    return (if (value < 0) "-" else "") + grouped + "." + fraction
}

/** An amount as its field shows it to edit: "85000.50", no grouping (what [amountInput] keeps). */
internal fun editableAmount(value: Double): String = formatAmount(value).replace(MONEY_SPACE.toString(), "")

internal fun formatMoney(value: Double, currency: Currency): String =
    formatAmount(value) + MONEY_SPACE + currency.name.uppercase()

/**
 * What an amount field keeps of what is typed: digits and one decimal separator (`.` or `,`), at most two decimals and
 * twelve digits before them. The server takes two decimals and up to 1,000,000,000,000 (`Money.ValidateAmount`).
 */
internal fun amountInput(text: String): String = buildString {
    var separator = false
    var whole = 0
    var decimals = 0
    for (c in text) {
        when {
            c.isDigit() && !separator && whole < 12 -> append(c).also { whole++ }
            c.isDigit() && separator && decimals < 2 -> append(c).also { decimals++ }
            (c == '.' || c == ',') && !separator -> append(c).also { separator = true }
        }
    }
}

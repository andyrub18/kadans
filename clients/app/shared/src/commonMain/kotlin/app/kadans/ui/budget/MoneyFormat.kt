package app.kadans.ui.budget

import app.kadans.api.model.Currency
import kotlin.math.abs
import kotlin.math.roundToLong

/** "85 000.50" — space-grouped thousands (the local convention), always two decimals. */
internal fun formatAmount(value: Double): String {
    val cents = (abs(value) * 100).roundToLong()
    val whole = cents / 100
    val fraction = (cents % 100).toString().padStart(2, '0')
    val grouped = whole.toString().reversed().chunked(3).joinToString(" ").reversed()
    return (if (value < 0) "-" else "") + grouped + "." + fraction
}

internal fun formatMoney(value: Double, currency: Currency): String =
    formatAmount(value) + " " + currency.name.uppercase()

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

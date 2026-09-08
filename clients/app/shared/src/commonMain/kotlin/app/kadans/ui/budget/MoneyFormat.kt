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

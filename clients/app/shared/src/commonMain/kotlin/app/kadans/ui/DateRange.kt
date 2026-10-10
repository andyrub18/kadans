package app.kadans.ui

import androidx.compose.material3.SelectableDates
import kotlinx.datetime.LocalDate

/** The days a date picker offers, [first] to [last] (open-ended when null). The DatePicker speaks UTC-midnight millis. */
internal class DateRange(private val first: LocalDate?, private val last: LocalDate?) : SelectableDates {
    override fun isSelectableDate(utcTimeMillis: Long): Boolean =
        LocalDate.fromEpochDays((utcTimeMillis / 86_400_000L).toInt()).let { (first == null || it >= first) && (last == null || it <= last) }

    override fun isSelectableYear(year: Int): Boolean = (first == null || year >= first.year) && (last == null || year <= last.year)
}

package fr.arichard.upupup.core

import android.content.Context
import android.text.format.DateFormat
import fr.arichard.upupup.R
import java.util.Calendar
import java.util.Date

/** Small formatting helpers shared across screens. */
object Format {

    /** "7h 32m" style remaining-time text for "rings in …" banners. */
    fun delay(context: Context, millis: Long): String {
        val totalMinutes = (millis + 59_999) / 60_000 // round up: "1m" until it actually rings
        val days = totalMinutes / (24 * 60)
        val hours = totalMinutes % (24 * 60) / 60
        val minutes = totalMinutes % 60
        return when {
            days > 0 -> context.getString(R.string.duration_days, days, hours)
            hours > 0 -> context.getString(R.string.duration_hours, hours, minutes)
            minutes > 0 -> context.getString(R.string.duration_minutes, minutes)
            else -> context.getString(R.string.duration_less_than_minute)
        }
    }

    /** Locale-aware clock time, e.g. "07:30" or "7:30 AM". */
    fun time(context: Context, epochMillis: Long): String =
        DateFormat.getTimeFormat(context).format(Date(epochMillis))

    /** Short weekday summary for an alarm: "Mon, Tue, Fri", "Every day", "Once"… */
    fun days(context: Context, days: Set<Int>): String {
        if (days.isEmpty()) return context.getString(R.string.once)
        if (days.size == 7) return context.getString(R.string.every_day)
        val weekdays = setOf(
            Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY,
            Calendar.THURSDAY, Calendar.FRIDAY
        )
        if (days == weekdays) return context.getString(R.string.weekdays)
        if (days == setOf(Calendar.SATURDAY, Calendar.SUNDAY)) {
            return context.getString(R.string.weekend)
        }
        val symbols = java.text.DateFormatSymbols().shortWeekdays
        // Order Monday-first for display.
        val order = listOf(
            Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY,
            Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY
        )
        return order.filter { it in days }.joinToString(", ") { symbols[it] }
    }
}

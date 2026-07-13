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

    /** Stopwatch elapsed time: "mm:ss.d", or "h:mm:ss.d" past an hour. */
    fun stopwatch(millis: Long): String {
        val tenths = millis / 100 % 10
        val totalSeconds = millis / 1000
        val seconds = totalSeconds % 60
        val minutes = totalSeconds / 60 % 60
        val hours = totalSeconds / 3600
        return if (hours > 0) {
            String.format(java.util.Locale.ROOT, "%d:%02d:%02d.%d", hours, minutes, seconds, tenths)
        } else {
            String.format(java.util.Locale.ROOT, "%02d:%02d.%d", minutes, seconds, tenths)
        }
    }

    /** Locale-aware clock time, e.g. "07:30" or "7:30 AM". */
    fun time(context: Context, epochMillis: Long): String =
        DateFormat.getTimeFormat(context).format(Date(epochMillis))

    /** Human name of a concrete sound output (not [Output.DEFAULT]). */
    fun output(context: Context, output: Output): String = when (output) {
        Output.SPEAKER -> context.getString(R.string.output_speaker)
        Output.WIRED -> context.getString(R.string.output_wired)
        Output.BLUETOOTH -> context.getString(R.string.output_bluetooth)
        Output.AUTO, Output.DEFAULT -> context.getString(R.string.output_auto)
    }

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

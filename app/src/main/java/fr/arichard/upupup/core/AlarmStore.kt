package fr.arichard.upupup.core

import android.content.Context
import org.json.JSONArray

/**
 * SharedPreferences-backed alarm storage plus transient snooze state.
 * All methods are cheap and synchronous; callers use them from the main thread.
 */
class AlarmStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("alarms", Context.MODE_PRIVATE)

    fun all(): List<Alarm> {
        val raw = prefs.getString(KEY_ALARMS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { Alarm.fromJson(array.getJSONObject(it)) }
        }.getOrDefault(emptyList())
            .sortedWith(compareBy({ it.hour }, { it.minute }, { it.id }))
    }

    fun get(id: Long): Alarm? = all().firstOrNull { it.id == id }

    fun save(alarm: Alarm) = write(all().filter { it.id != alarm.id } + alarm)

    fun delete(id: Long) {
        write(all().filter { it.id != id })
        clearSnooze(id)
    }

    private fun write(alarms: List<Alarm>) {
        val array = JSONArray().apply { alarms.forEach { put(it.toJson()) } }
        prefs.edit().putString(KEY_ALARMS, array.toString()).apply()
    }

    // ---- Snooze state (per alarm, survives process death) ----

    /** Epoch millis the snoozed alarm should re-ring at, or 0 if not snoozed. */
    fun snoozeUntil(id: Long): Long = prefs.getLong("snooze_until_$id", 0)

    fun snoozeCount(id: Long): Int = prefs.getInt("snooze_count_$id", 0)

    fun recordSnooze(id: Long, until: Long) {
        prefs.edit()
            .putLong("snooze_until_$id", until)
            .putInt("snooze_count_$id", snoozeCount(id) + 1)
            .apply()
    }

    /** Called when the alarm actually fires: the pending snooze time is consumed. */
    fun consumeSnoozeTime(id: Long) {
        val editor = prefs.edit().remove("snooze_until_$id")
        // No pending snooze means a fresh ring cycle: a count left over from a cycle
        // that ended without a dismiss (e.g. interrupted by another alarm) must not
        // eat into this cycle's snooze budget.
        if (snoozeUntil(id) == 0L) editor.remove("snooze_count_$id")
        editor.apply()
    }

    /** Called on dismiss: everything about the ring cycle is over. */
    fun clearSnooze(id: Long) {
        prefs.edit().remove("snooze_until_$id").remove("snooze_count_$id").apply()
    }

    private companion object {
        const val KEY_ALARMS = "alarms_json"
    }
}

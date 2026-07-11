package fr.arichard.upupup.core

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/** Wake-up mission required to stop the alarm. */
enum class Mission { NONE, SHAKE, MATH }

/** Where the alarm sound is routed. */
enum class Output { AUTO, SPEAKER, WIRED, BLUETOOTH }

/**
 * One alarm, everything included. Persisted as JSON in SharedPreferences —
 * a handful of alarms doesn't justify a database.
 */
data class Alarm(
    val id: Long,
    val hour: Int,
    val minute: Int,
    /** Calendar.MONDAY..SUNDAY constants; empty = one-shot alarm. */
    val days: Set<Int> = emptySet(),
    val label: String = "",
    val enabled: Boolean = true,
    /** Ringtone URI as string; null = system default alarm sound. */
    val soundUri: String? = null,
    /** 1..100, percentage of the device's max alarm volume. */
    val volume: Int = 80,
    /** Ramp from silent to [volume] over the first minute. */
    val rampUp: Boolean = true,
    val vibrate: Boolean = true,
    /** Snooze length in minutes; 0 = snooze disabled. */
    val snoozeMinutes: Int = 5,
    /** Max snoozes per ring; 0 = unlimited. */
    val maxSnoozes: Int = 0,
    val mission: Mission = Mission.NONE,
    /** SHAKE: number of shakes. MATH: difficulty 1..3. */
    val missionLevel: Int = 0,
    val output: Output = Output.AUTO,
) {

    /**
     * Next time this alarm should ring, in epoch millis, strictly after [now].
     * One-shot alarms ring at the next occurrence of hour:minute (today or tomorrow).
     */
    fun nextTrigger(now: Long = System.currentTimeMillis()): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (days.isEmpty()) {
            if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_YEAR, 1)
            return cal.timeInMillis
        }
        // Repeating: walk forward at most 7 days to the next selected weekday.
        repeat(8) {
            if (cal.timeInMillis > now && cal.get(Calendar.DAY_OF_WEEK) in days) {
                return cal.timeInMillis
            }
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis // unreachable
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("hour", hour)
        put("minute", minute)
        put("days", JSONArray(days.toList()))
        put("label", label)
        put("enabled", enabled)
        put("soundUri", soundUri ?: JSONObject.NULL)
        put("volume", volume)
        put("rampUp", rampUp)
        put("vibrate", vibrate)
        put("snoozeMinutes", snoozeMinutes)
        put("maxSnoozes", maxSnoozes)
        put("mission", mission.name)
        put("missionLevel", missionLevel)
        put("output", output.name)
    }

    companion object {
        fun fromJson(json: JSONObject): Alarm {
            val days = mutableSetOf<Int>()
            val array = json.optJSONArray("days") ?: JSONArray()
            for (i in 0 until array.length()) days.add(array.getInt(i))
            return Alarm(
                id = json.getLong("id"),
                hour = json.getInt("hour"),
                minute = json.getInt("minute"),
                days = days,
                label = json.optString("label"),
                enabled = json.optBoolean("enabled", true),
                soundUri = if (json.isNull("soundUri")) null else json.optString("soundUri"),
                volume = json.optInt("volume", 80),
                rampUp = json.optBoolean("rampUp", true),
                vibrate = json.optBoolean("vibrate", true),
                snoozeMinutes = json.optInt("snoozeMinutes", 5),
                maxSnoozes = json.optInt("maxSnoozes", 0),
                mission = runCatching { Mission.valueOf(json.optString("mission")) }
                    .getOrDefault(Mission.NONE),
                missionLevel = json.optInt("missionLevel", 0),
                output = runCatching { Output.valueOf(json.optString("output")) }
                    .getOrDefault(Output.AUTO),
            )
        }
    }
}

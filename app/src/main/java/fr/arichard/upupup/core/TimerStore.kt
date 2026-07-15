package fr.arichard.upupup.core

import android.content.Context

/** Persists the single countdown timer so it survives process death. */
class TimerStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("timer", Context.MODE_PRIVATE)

    /** Epoch millis the running timer ends at; 0 = no timer running. */
    var endTime: Long
        get() = prefs.getLong("end_time", 0)
        set(value) = prefs.edit().putLong("end_time", value).apply()

    /** Milliseconds left on a paused timer; 0 = not paused. */
    var pausedRemaining: Long
        get() = prefs.getLong("paused_remaining", 0)
        set(value) = prefs.edit().putLong("paused_remaining", value).apply()

    /** Last duration the user picked, to prefill the pickers. Seconds. */
    var lastDuration: Int
        get() = prefs.getInt("last_duration", 10 * 60)
        set(value) = prefs.edit().putInt("last_duration", value).apply()

    /** Sound output the timer rings on (a concrete choice, never [Output.DEFAULT]). */
    var output: Output
        get() = runCatching { Output.valueOf(prefs.getString("output", null) ?: "") }
            .getOrDefault(Output.AUTO)
        set(value) = prefs.edit().putString("output", value.name).apply()

    val isRunning: Boolean get() = endTime > System.currentTimeMillis()

    val isPaused: Boolean get() = pausedRemaining > 0

    fun clear() {
        prefs.edit().remove("end_time").remove("paused_remaining").apply()
    }
}

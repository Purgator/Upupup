package fr.arichard.upupup.core

import android.content.Context

/** How the alarm time is picked in the editor. */
enum class TimePickerMode { WHEEL, CLOCK }

/** App-level settings. */
class Prefs(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var autoUpdate: Boolean
        get() = prefs.getBoolean("auto_update", true)
        set(value) = prefs.edit().putBoolean("auto_update", value).apply()

    /** Minutes before an alarm rings to post a heads-up notification; 0 = off. */
    var preAlarmMinutes: Int
        get() = prefs.getInt("pre_alarm_minutes", 10)
        set(value) = prefs.edit().putInt("pre_alarm_minutes", value).apply()

    /** Editor time-selection style. */
    var timePickerMode: TimePickerMode
        get() = runCatching {
            TimePickerMode.valueOf(prefs.getString("time_picker_mode", null) ?: "")
        }.getOrDefault(TimePickerMode.WHEEL)
        set(value) = prefs.edit().putString("time_picker_mode", value.name).apply()

    /** Snooze gesture on the ring screen: swipe up (default) or a tap button. */
    var swipeToSnooze: Boolean
        get() = prefs.getBoolean("swipe_to_snooze", true)
        set(value) = prefs.edit().putBoolean("swipe_to_snooze", value).apply()

    /** App-wide sound output; alarms with [Output.DEFAULT] follow this. */
    var defaultOutput: Output
        get() = runCatching { Output.valueOf(prefs.getString("default_output", null) ?: "") }
            .getOrDefault(Output.AUTO)
        set(value) = prefs.edit().putString("default_output", value.name).apply()

    var lastUpdateCheck: Long
        get() = prefs.getLong("last_update_check", 0)
        set(value) = prefs.edit().putLong("last_update_check", value).apply()

    var updateDeferred: Boolean
        get() = prefs.getBoolean("update_deferred", false)
        set(value) = prefs.edit().putBoolean("update_deferred", value).apply()

    /** One-time prompts we don't want to nag about. */
    var fullScreenPromptShown: Boolean
        get() = prefs.getBoolean("fullscreen_prompt_shown", false)
        set(value) = prefs.edit().putBoolean("fullscreen_prompt_shown", value).apply()
}

package fr.arichard.upupup.core

import android.content.Context

/** App-level settings. */
class Prefs(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var autoUpdate: Boolean
        get() = prefs.getBoolean("auto_update", true)
        set(value) = prefs.edit().putBoolean("auto_update", value).apply()

    /** Snooze gesture on the ring screen: swipe up (default) or a tap button. */
    var swipeToSnooze: Boolean
        get() = prefs.getBoolean("swipe_to_snooze", true)
        set(value) = prefs.edit().putBoolean("swipe_to_snooze", value).apply()

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

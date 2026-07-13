package fr.arichard.upupup.core

import android.content.Context
import android.os.SystemClock
import org.json.JSONArray

/**
 * Persists the stopwatch so it keeps counting across tab switches, process death and
 * screen rotation. Timing is based on [SystemClock.elapsedRealtime] (monotonic, immune
 * to clock changes); only offsets are stored, never wall-clock values.
 */
class StopwatchStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("stopwatch", Context.MODE_PRIVATE)

    var running: Boolean
        get() = prefs.getBoolean("running", false)
        private set(value) = prefs.edit().putBoolean("running", value).apply()

    /** elapsedRealtime at which the current run segment started. */
    private var startBase: Long
        get() = prefs.getLong("start_base", 0)
        set(value) = prefs.edit().putLong("start_base", value).apply()

    /** Milliseconds accumulated before the current run segment. */
    private var accumulated: Long
        get() = prefs.getLong("accumulated", 0)
        set(value) = prefs.edit().putLong("accumulated", value).apply()

    /** Total elapsed time in milliseconds, running or paused. */
    fun elapsed(): Long =
        if (running) accumulated + (SystemClock.elapsedRealtime() - startBase) else accumulated

    fun start() {
        if (running) return
        startBase = SystemClock.elapsedRealtime()
        running = true
    }

    fun pause() {
        if (!running) return
        accumulated += SystemClock.elapsedRealtime() - startBase
        running = false
    }

    fun reset() {
        prefs.edit().clear().apply()
    }

    // ---- Laps ----

    /** Cumulative elapsed time (ms) captured at each lap, oldest first. */
    fun laps(): List<Long> {
        val raw = prefs.getString("laps", null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { array.getLong(it) }
        }.getOrDefault(emptyList())
    }

    fun addLap() {
        val laps = laps() + elapsed()
        prefs.edit().putString("laps", JSONArray(laps).toString()).apply()
    }
}

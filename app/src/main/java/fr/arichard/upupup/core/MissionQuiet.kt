package fr.arichard.upupup.core

/**
 * Pure policy for the "quiet lease" granted while the user is actively doing a mission.
 *
 * Every mission activity grants a quiet lease whose length is [windowMs]. If the lease runs
 * out without further activity (a lapse), the alarm rings again and the next lease is
 * shorter, down to the last (floor) window. Activity never resets the lapse count.
 * The caller silences sound and vibration while [isQuiet].
 *
 * The class holds no clock and no Android dependency: the caller owns the timers and only
 * reports [onActivity] / [onLapse] events. Not thread-safe; use from a single thread.
 */
class MissionQuiet(windowsMs: LongArray = DEFAULT_WINDOWS_MS) {

    private val windows: LongArray = windowsMs.copyOf()

    init {
        require(windows.isNotEmpty()) { "windowsMs must not be empty" }
        require(windows.all { it > 0L }) { "windowsMs must all be positive" }
    }

    /** True while the alarm is quiet (a lease is running). */
    var isQuiet: Boolean = false
        private set

    /** Number of times a quiet lease has run out. */
    var lapses: Int = 0
        private set

    /** Length of the quiet lease to arm now; decreases with [lapses] and stays at the floor. */
    val windowMs: Long
        get() = windows[minOf(lapses, windows.lastIndex)]

    /** Reports mission activity. Returns true only when this transitions ringing -> quiet. */
    fun onActivity(): Boolean {
        if (isQuiet) return false
        isQuiet = true
        return true
    }

    /**
     * Reports that the quiet lease ran out. Returns true only when this transitions
     * quiet -> ringing, in which case [lapses] is incremented.
     */
    fun onLapse(): Boolean {
        if (!isQuiet) return false
        isQuiet = false
        lapses++
        return true
    }

    companion object {
        /** Quiet lease lengths: 30 s, then 20 s, 15 s, and finally 10 s for every later lease. */
        val DEFAULT_WINDOWS_MS: LongArray = longArrayOf(30_000L, 20_000L, 15_000L, 10_000L)

        /** Whether vibration is wanted and the quiet lease is not active. */
        fun shouldVibrate(wanted: Boolean, quiet: MissionQuiet?): Boolean =
            wanted && quiet?.isQuiet != true
    }
}

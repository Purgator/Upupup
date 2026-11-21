package fr.arichard.upupup.core

import android.content.Context

/**
 * State transitions of the countdown timer, shared by the in-app buttons and the
 * notification actions. Every transition keeps the AlarmManager registration in sync;
 * callers refresh whatever UI/notification they own afterwards.
 */
object TimerController {

    /** Running → paused: remember what is left and disarm the alarm. */
    fun pause(context: Context) {
        val store = TimerStore(context)
        val remaining = store.endTime - System.currentTimeMillis()
        if (remaining <= 0) return // already fired (or about to): nothing to pause
        store.pausedRemaining = remaining
        store.endTime = 0
        AlarmScheduler.cancel(context, AlarmScheduler.TIMER_ID)
    }

    /** Paused → running: re-arm for the remembered remaining time. */
    fun resume(context: Context) {
        val store = TimerStore(context)
        val remaining = store.pausedRemaining
        if (remaining <= 0) return
        store.endTime = System.currentTimeMillis() + remaining
        store.pausedRemaining = 0
        AlarmScheduler.scheduleAll(context)
    }

    /** Adds a minute to a running or paused timer. */
    fun plusOneMinute(context: Context) = plusMinutes(context, 1)

    /** Adds [minutes] to a running or paused timer. */
    fun plusMinutes(context: Context, minutes: Int) {
        val store = TimerStore(context)
        when {
            store.isRunning -> {
                store.endTime += minutes * 60_000L
                AlarmScheduler.scheduleAll(context)
            }
            store.isPaused -> store.pausedRemaining += minutes * 60_000L
        }
    }

    /** Stops and forgets the timer entirely. */
    fun reset(context: Context) {
        TimerStore(context).clear()
        AlarmScheduler.cancel(context, AlarmScheduler.TIMER_ID)
    }
}

package fr.arichard.upupup.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * AlarmManager fires here at the exact trigger time. Hands off to [AlarmService]
 * immediately — a receiver only gets ~10s of guaranteed execution.
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ID, -1)
        if (id < 0) return

        if (id != AlarmScheduler.TIMER_ID) {
            val store = AlarmStore(context)
            val alarm = store.get(id) ?: return
            // The pending snooze (if any) just fired; it must not be rescheduled.
            store.consumeSnoozeTime(id)
            if (alarm.days.isNotEmpty()) {
                // Repeating: arm the next occurrence right away so it is guaranteed
                // even if the user force-stops the ringing screen.
                AlarmScheduler.schedule(context, alarm, store)
            } else {
                store.save(alarm.copy(enabled = false))
            }
        } else {
            TimerStore(context).clear()
        }

        ContextCompat.startForegroundService(
            context,
            Intent(context, AlarmService::class.java).putExtra(EXTRA_ID, id)
        )
    }

    companion object {
        const val EXTRA_ID = "alarm_id"
    }
}

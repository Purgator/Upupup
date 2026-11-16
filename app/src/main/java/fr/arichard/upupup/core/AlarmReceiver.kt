package fr.arichard.upupup.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * AlarmManager fires here at the exact trigger time (or the pre-alarm lead time).
 * The real trigger hands off to [AlarmService] immediately — a receiver only gets
 * ~10s of guaranteed execution. Notification actions land here too.
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_RENOTIFY) {
            AlarmService.renotify(context)
            return
        }

        val id = intent.getLongExtra(EXTRA_ID, -1)
        if (id < 0) return

        when (intent.action) {
            ACTION_PRE_ALARM -> {
                postUpcomingNotification(context, id)
                return
            }
            UpcomingAlarm.ACTION_DISMISS -> {
                val store = AlarmStore(context)
                val alarm = store.get(id)
                if (alarm != null && alarm.enabled) {
                    UpcomingAlarm.dismissNext(context, alarm, store)
                } else {
                    UpcomingAlarm.cancelNotification(context, id)
                }
                return
            }
        }

        // Real trigger: clear any heads-up we posted for this alarm.
        UpcomingAlarm.cancelNotification(context, id)

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

    private fun postUpcomingNotification(context: Context, id: Long) {
        val store = AlarmStore(context)
        val alarm = store.get(id) ?: return
        if (!alarm.enabled) return
        UpcomingAlarm.notify(context, alarm, store.nextRing(alarm), snoozed = false)
    }

    companion object {
        const val EXTRA_ID = "alarm_id"
        const val ACTION_PRE_ALARM = "fr.arichard.upupup.action.PRE_ALARM"

        /** The ringing notification was swiped away: put it back. */
        const val ACTION_RENOTIFY = "fr.arichard.upupup.action.RENOTIFY"
    }
}

package fr.arichard.upupup.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import fr.arichard.upupup.MainActivity
import fr.arichard.upupup.R

/**
 * AlarmManager fires here at the exact trigger time (or the pre-alarm lead time).
 * The real trigger hands off to [AlarmService] immediately — a receiver only gets
 * ~10s of guaranteed execution.
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ID, -1)
        if (id < 0) return

        if (intent.action == ACTION_PRE_ALARM) {
            postUpcomingNotification(context, id)
            return
        }

        // Real trigger: clear any heads-up we posted for this alarm.
        NotificationManagerCompat(context).cancelUpcoming(id)

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
        val alarm = AlarmStore(context).get(id) ?: return
        if (!alarm.enabled) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID, context.getString(R.string.upcoming_channel),
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
        val at = alarm.nextTrigger()
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val minutes = ((at - System.currentTimeMillis()) / 60_000).coerceAtLeast(0).toInt() + 1
        val title = if (alarm.label.isNotBlank()) alarm.label
        else context.getString(R.string.app_name)
        manager.notify(
            upcomingNotificationId(id),
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_alarm)
                .setContentTitle(title)
                .setContentText(
                    context.getString(R.string.upcoming_text, minutes, Format.time(context, at))
                )
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
        )
    }

    companion object {
        const val EXTRA_ID = "alarm_id"
        const val ACTION_PRE_ALARM = "fr.arichard.upupup.action.PRE_ALARM"
        private const val CHANNEL_ID = "upcoming"

        /** Stable, collision-free notification id for an alarm's heads-up. */
        fun upcomingNotificationId(id: Long): Int = 100 + (id % 100_000).toInt()
    }
}

/** Tiny wrapper so the heads-up can be cancelled from one place. */
private class NotificationManagerCompat(private val context: Context) {
    fun cancelUpcoming(id: Long) {
        context.getSystemService(NotificationManager::class.java)
            .cancel(AlarmReceiver.upcomingNotificationId(id))
    }
}

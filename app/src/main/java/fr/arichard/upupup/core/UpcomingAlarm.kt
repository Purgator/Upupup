package fr.arichard.upupup.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import fr.arichard.upupup.MainActivity
import fr.arichard.upupup.R

/**
 * Everything about a ring that has not happened yet: dismissing it in advance (from the
 * heads-up notification or the alarm card) and the heads-up notification itself.
 */
object UpcomingAlarm {

    const val ACTION_DISMISS = "fr.arichard.upupup.action.DISMISS_UPCOMING"
    private const val CHANNEL_ID = "upcoming"

    /** How long before its ring an alarm card offers to dismiss it. */
    const val CARD_WINDOW_MS = 2L * 60 * 60 * 1000

    /**
     * Cancels only the next ring and returns its time. A repeating alarm stays enabled:
     * its pending snooze is dropped, or else its next occurrence is skipped, and the
     * cancelled time is remembered so the card can show it until it has passed. A
     * one-shot alarm has nothing left to ring, so it is turned off (returns 0).
     */
    fun dismissNext(
        context: Context, alarm: Alarm, store: AlarmStore = AlarmStore(context),
    ): Long {
        if (alarm.days.isEmpty()) {
            turnOff(context, alarm, store)
            return 0
        }
        val now = System.currentTimeMillis()
        // Snoozed: nextRing() is the snooze time, and skipping "until" it leaves every
        // regular occurrence untouched.
        val cancelled = store.nextRing(alarm, now)
        store.clearSnooze(alarm.id)
        store.setSkipUntil(alarm.id, cancelled)
        AlarmScheduler.schedule(context, alarm, store)
        cancelNotification(context, alarm.id)
        return cancelled
    }

    /** Disables the alarm entirely: pending snooze and every future occurrence. */
    fun turnOff(context: Context, alarm: Alarm, store: AlarmStore = AlarmStore(context)) {
        store.clearSnooze(alarm.id)
        store.clearSkip(alarm.id)
        val off = alarm.copy(enabled = false)
        store.save(off)
        AlarmScheduler.schedule(context, off, store)
    }

    /** Undoes [dismissNext] for a repeating alarm whose next occurrence was skipped. */
    fun restore(context: Context, alarm: Alarm, store: AlarmStore = AlarmStore(context)) {
        store.clearSkip(alarm.id)
        AlarmScheduler.schedule(context, alarm, store)
    }

    // ---- Heads-up notification ----

    /** Stable, collision-free notification id for an alarm's heads-up. */
    fun notificationId(id: Long): Int = 100 + (id % 100_000).toInt()

    /**
     * Posts the "rings soon" notification with its dismiss action. [snoozed] rings are
     * announced silently: the user just touched the phone to snooze.
     */
    fun notify(context: Context, alarm: Alarm, at: Long, snoozed: Boolean) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID, context.getString(R.string.upcoming_channel),
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), flags
        )
        val dismiss = PendingIntent.getBroadcast(
            context,
            (alarm.id % Int.MAX_VALUE).toInt(),
            Intent(context, AlarmReceiver::class.java)
                .setAction(ACTION_DISMISS)
                .putExtra(AlarmReceiver.EXTRA_ID, alarm.id),
            flags
        )
        val time = Format.time(context, at)
        val text = if (snoozed) {
            context.getString(R.string.snoozed_until, time)
        } else {
            val minutes = ((at - System.currentTimeMillis()) / 60_000).coerceAtLeast(0).toInt() + 1
            context.getString(R.string.upcoming_text, minutes, time)
        }
        val title = if (alarm.label.isNotBlank()) alarm.label
        else context.getString(R.string.app_name)
        manager.notify(
            notificationId(alarm.id),
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_alarm)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(open)
                .setAutoCancel(true)
                .setSilent(snoozed)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .addAction(0, context.getString(R.string.skip_this_ring), dismiss)
                .build()
        )
    }

    fun cancelNotification(context: Context, id: Long) {
        context.getSystemService(NotificationManager::class.java).cancel(notificationId(id))
    }
}

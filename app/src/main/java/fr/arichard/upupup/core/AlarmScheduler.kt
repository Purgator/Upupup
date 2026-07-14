package fr.arichard.upupup.core

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import fr.arichard.upupup.MainActivity

/**
 * Registers alarms with [AlarmManager.setAlarmClock] — the strongest guarantee Android
 * offers: exact trigger, exempt from Doze and battery optimizations, and the system
 * shows the alarm-clock icon in the status bar so the user always knows one is set.
 */
object AlarmScheduler {

    private const val TAG = "AlarmScheduler"

    /** Request-code namespace for the countdown timer (alarm ids are epoch millis, always > 1000). */
    const val TIMER_ID = 1L

    /** Re-syncs every alarm (and the timer) with AlarmManager. Idempotent. */
    fun scheduleAll(context: Context) {
        val store = AlarmStore(context)
        store.all().forEach { schedule(context, it, store) }
        TimerStore(context).endTime.takeIf { it > System.currentTimeMillis() }?.let {
            setExact(context, TIMER_ID, it)
        }
    }

    /** (Re)arms one alarm, honouring a pending snooze, or cancels it when disabled. */
    fun schedule(context: Context, alarm: Alarm, store: AlarmStore = AlarmStore(context)) {
        if (!alarm.enabled) {
            cancel(context, alarm.id)
            return
        }
        val snoozed = store.snoozeUntil(alarm.id)
        val at = if (snoozed > System.currentTimeMillis()) snoozed else alarm.nextTrigger()
        setExact(context, alarm.id, at)
        schedulePreAlarm(context, alarm.id, at)
    }

    fun cancel(context: Context, id: Long) {
        val manager = alarmManager(context)
        manager.cancel(firePendingIntent(context, id))
        manager.cancel(preAlarmPendingIntent(context, id))
    }

    /** Heads-up notification a few minutes before the alarm; skipped when too close or off. */
    private fun schedulePreAlarm(context: Context, id: Long, triggerAt: Long) {
        val manager = alarmManager(context)
        manager.cancel(preAlarmPendingIntent(context, id))
        val lead = Prefs(context).preAlarmMinutes
        if (lead <= 0) return
        val at = triggerAt - lead * 60_000L
        if (at <= System.currentTimeMillis()) return
        if (Build.VERSION.SDK_INT in 31..32 && !manager.canScheduleExactAlarms()) return
        manager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP, at, preAlarmPendingIntent(context, id)
        )
    }

    private fun setExact(context: Context, id: Long, triggerAt: Long) {
        val manager = alarmManager(context)
        if (Build.VERSION.SDK_INT in 31..32 && !manager.canScheduleExactAlarms()) {
            Log.w(TAG, "Exact alarm permission missing; alarm $id not scheduled")
            return
        }
        // Tapping the status-bar alarm icon opens the app.
        val show = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        manager.setAlarmClock(
            AlarmManager.AlarmClockInfo(triggerAt, show),
            firePendingIntent(context, id)
        )
        Log.i(TAG, "Alarm $id set for ${java.util.Date(triggerAt)}")
    }

    private fun firePendingIntent(context: Context, id: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            (id % Int.MAX_VALUE).toInt(),
            Intent(context, AlarmReceiver::class.java).putExtra(AlarmReceiver.EXTRA_ID, id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    /** Distinct from the fire intent via its action, so both can coexist per alarm. */
    private fun preAlarmPendingIntent(context: Context, id: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            (id % Int.MAX_VALUE).toInt(),
            Intent(context, AlarmReceiver::class.java)
                .setAction(AlarmReceiver.ACTION_PRE_ALARM)
                .putExtra(AlarmReceiver.EXTRA_ID, id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun alarmManager(context: Context) =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
}

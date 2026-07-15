package fr.arichard.upupup.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import fr.arichard.upupup.MainActivity
import fr.arichard.upupup.R
import java.util.Locale

/**
 * The timer and stopwatch shade notifications, with their pause/resume/lap/reset
 * actions. Posted only while the app is NOT in the foreground — [MainActivity] cancels
 * them on resume and re-posts them on pause — so the shade never duplicates what is
 * already on screen.
 */
object TickingNotifications {

    const val TIMER_ID = AlarmService.NOTIFICATION_ID_TIMER
    const val STOPWATCH_ID = 4

    // Notification-action commands, handled by [ActionReceiver].
    private const val ACTION_TIMER_PAUSE = "fr.arichard.upupup.action.TIMER_PAUSE"
    private const val ACTION_TIMER_PLUS_ONE = "fr.arichard.upupup.action.TIMER_PLUS_ONE"
    private const val ACTION_TIMER_RESUME = "fr.arichard.upupup.action.TIMER_RESUME"
    private const val ACTION_TIMER_RESET = "fr.arichard.upupup.action.TIMER_RESET"
    private const val ACTION_STOPWATCH_PAUSE = "fr.arichard.upupup.action.STOPWATCH_PAUSE"
    private const val ACTION_STOPWATCH_LAP = "fr.arichard.upupup.action.STOPWATCH_LAP"
    private const val ACTION_STOPWATCH_RESUME = "fr.arichard.upupup.action.STOPWATCH_RESUME"
    private const val ACTION_STOPWATCH_RESET = "fr.arichard.upupup.action.STOPWATCH_RESET"

    /** Posts (or refreshes) the timer notification matching the current store state. */
    fun showTimer(context: Context) {
        val store = TimerStore(context)
        val manager = manager(context)
        when {
            store.isRunning -> {
                ensureChannel(context, "timer", R.string.timer_running_channel)
                val end = store.endTime
                val builder = base(context, "timer")
                    .setContentTitle(context.getString(R.string.timer_notification_title))
                    .setContentText(
                        context.getString(R.string.timer_notification_text, Format.time(context, end))
                    )
                    .setUsesChronometer(true)
                    .setChronometerCountDown(true)
                    .setWhen(end)
                    .setOngoing(true)
                    .addAction(0, context.getString(R.string.stopwatch_pause),
                        action(context, ACTION_TIMER_PAUSE, 10))
                    .addAction(0, context.getString(R.string.plus_one_minute),
                        action(context, ACTION_TIMER_PLUS_ONE, 11))
                manager.notify(TIMER_ID, builder.build())
            }
            store.isPaused -> {
                ensureChannel(context, "timer", R.string.timer_running_channel)
                val builder = base(context, "timer")
                    .setContentTitle(context.getString(R.string.timer_paused_title))
                    .setContentText(hms(store.pausedRemaining))
                    .setShowWhen(false)
                    .setOngoing(true)
                    .addAction(0, context.getString(R.string.action_resume),
                        action(context, ACTION_TIMER_RESUME, 12))
                    .addAction(0, context.getString(R.string.stopwatch_reset),
                        action(context, ACTION_TIMER_RESET, 13))
                manager.notify(TIMER_ID, builder.build())
            }
            else -> manager.cancel(TIMER_ID)
        }
    }

    /** Posts (or refreshes) the stopwatch notification matching the current store state. */
    fun showStopwatch(context: Context) {
        val store = StopwatchStore(context)
        val manager = manager(context)
        when {
            store.running -> {
                ensureChannel(context, "stopwatch", R.string.stopwatch_notification_title)
                val builder = base(context, "stopwatch", R.drawable.ic_stopwatch)
                    .setContentTitle(context.getString(R.string.stopwatch_notification_title))
                    .setUsesChronometer(true)
                    .setWhen(System.currentTimeMillis() - store.elapsed())
                    .setOngoing(true)
                    .addAction(0, context.getString(R.string.stopwatch_pause),
                        action(context, ACTION_STOPWATCH_PAUSE, 14))
                    .addAction(0, context.getString(R.string.stopwatch_lap),
                        action(context, ACTION_STOPWATCH_LAP, 15))
                manager.notify(STOPWATCH_ID, builder.build())
            }
            store.elapsed() > 0 -> {
                ensureChannel(context, "stopwatch", R.string.stopwatch_notification_title)
                val builder = base(context, "stopwatch", R.drawable.ic_stopwatch)
                    .setContentTitle(context.getString(R.string.stopwatch_paused_title))
                    .setContentText(Format.stopwatch(store.elapsed()))
                    .setShowWhen(false)
                    .addAction(0, context.getString(R.string.timer_start),
                        action(context, ACTION_STOPWATCH_RESUME, 16))
                    .addAction(0, context.getString(R.string.stopwatch_reset),
                        action(context, ACTION_STOPWATCH_RESET, 17))
                manager.notify(STOPWATCH_ID, builder.build())
            }
            else -> manager.cancel(STOPWATCH_ID)
        }
    }

    fun cancelTimer(context: Context) = manager(context).cancel(TIMER_ID)

    fun cancelStopwatch(context: Context) = manager(context).cancel(STOPWATCH_ID)

    /** Handles the notification-action taps. Runs while the app is in the background. */
    class ActionReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_TIMER_PAUSE -> TimerController.pause(context)
                ACTION_TIMER_PLUS_ONE -> TimerController.plusOneMinute(context)
                ACTION_TIMER_RESUME -> TimerController.resume(context)
                ACTION_TIMER_RESET -> TimerController.reset(context)
                ACTION_STOPWATCH_PAUSE -> StopwatchStore(context).pause()
                ACTION_STOPWATCH_LAP -> StopwatchStore(context).addLap()
                ACTION_STOPWATCH_RESUME -> StopwatchStore(context).start()
                ACTION_STOPWATCH_RESET -> StopwatchStore(context).reset()
                else -> return
            }
            when (intent.action) {
                ACTION_TIMER_PAUSE, ACTION_TIMER_PLUS_ONE,
                ACTION_TIMER_RESUME, ACTION_TIMER_RESET -> showTimer(context)
                else -> showStopwatch(context)
            }
        }
    }

    // ---- Plumbing ----

    private fun manager(context: Context) =
        context.getSystemService(NotificationManager::class.java)

    private fun ensureChannel(context: Context, id: String, nameRes: Int) {
        manager(context).createNotificationChannel(
            NotificationChannel(id, context.getString(nameRes), NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun base(
        context: Context,
        channel: String,
        icon: Int = R.drawable.ic_alarm,
    ): NotificationCompat.Builder {
        val open = PendingIntent.getActivity(
            context, 2, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(context, channel)
            .setSmallIcon(icon)
            .setContentIntent(open)
    }

    private fun action(context: Context, action: String, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context, requestCode,
            Intent(context, ActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun hms(millis: Long): String {
        val s = (millis / 1000).coerceAtLeast(0)
        return String.format(Locale.ROOT, "%02d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60)
    }
}

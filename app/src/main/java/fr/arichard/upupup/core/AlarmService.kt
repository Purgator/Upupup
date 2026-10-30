package fr.arichard.upupup.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.app.NotificationCompat
import fr.arichard.upupup.R
import fr.arichard.upupup.RingActivity

/**
 * Foreground service that actually rings: looping sound, vibration, and a full-screen
 * notification that pops [RingActivity] over the lock screen. Runs until dismissed,
 * snoozed, or the safety timeout auto-snoozes it.
 */
class AlarmService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var previousVolume = -1
    private var audioManager: AudioManager? = null
    private var missionQuiet: MissionQuiet? = null
    private var rampActive = false
    private val lapseRunnable = Runnable { onQuietLapse() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SNOOZE -> { snooze(); return START_NOT_STICKY }
            ACTION_DISMISS -> { dismiss(); return START_NOT_STICKY }
            ACTION_MISSION_ACTIVE -> {
                onMissionActive(intent.getLongExtra(EXTRA_MISSION_ALARM_ID, -1), startId)
                return START_NOT_STICKY
            }
            ACTION_MISSION_LEFT -> {
                onMissionLeft(intent.getLongExtra(EXTRA_MISSION_ALARM_ID, -1), startId)
                return START_NOT_STICKY
            }
        }

        val id = intent?.getLongExtra(AlarmReceiver.EXTRA_ID, -1) ?: -1
        if (id < 0) {
            stopSelf()
            return START_NOT_STICKY
        }
        val isTimer = id == AlarmScheduler.TIMER_ID
        val alarm = if (isTimer) timerAlarm(this) else AlarmStore(this).get(id)
        if (alarm == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (current != null) stopRinging() // a second alarm fired while one was ringing

        current = alarm
        currentIsTimer = isTimer
        missionQuiet = if (!isTimer && alarm.mission != Mission.NONE && Prefs(this).missionQuiet) {
            MissionQuiet()
        } else {
            null
        }
        if (isTimer) {
            // Remove the countdown notification; the ringing one takes over.
            getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID_TIMER)
        }

        startForeground(NOTIFICATION_ID, buildNotification(alarm, isTimer))
        acquireWakeLock()
        startSound(alarm)
        if (alarm.vibrate) startVibration()

        // The full-screen intent only fires when the screen is off/locked. If the user
        // is currently *in* the app, launch the ring screen directly (allowed because
        // one of our activities is visible; silently ignored by the system otherwise).
        runCatching {
            startActivity(
                Intent(this, RingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }

        // Safety net: never ring forever. Auto-snooze (or stop) after 5 minutes.
        handler.postDelayed({ if (canSnooze(this)) snooze() else dismiss() }, AUTO_TIMEOUT_MS)
        return START_NOT_STICKY
    }

    private fun snooze() {
        val alarm = current ?: return
        val until = System.currentTimeMillis() + alarm.snoozeMinutes * 60_000L
        if (currentIsTimer) {
            TimerStore(this).endTime = until
            AlarmScheduler.scheduleAll(this)
        } else {
            val store = AlarmStore(this)
            store.recordSnooze(alarm.id, until)
            // A snoozed one-shot must come back even though it was disabled at fire time.
            if (alarm.days.isEmpty()) store.save(alarm.copy(enabled = true))
            AlarmScheduler.schedule(this, alarm.copy(enabled = true), store)
        }
        stopRinging()
        stopSelf()
    }

    private fun dismiss() {
        current?.let { alarm ->
            if (!currentIsTimer) {
                val store = AlarmStore(this)
                store.clearSnooze(alarm.id)
                // A dismissed snoozed one-shot must not ring again.
                if (alarm.days.isEmpty()) {
                    store.save(alarm.copy(enabled = false))
                    AlarmScheduler.cancel(this, alarm.id)
                }
            }
        }
        stopRinging()
        stopSelf()
    }

    private fun stopRinging() {
        handler.removeCallbacksAndMessages(null)
        missionQuiet = null
        rampActive = false
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        vibrator?.cancel()
        vibrator = null
        audioManager?.let { am ->
            if (previousVolume >= 0) {
                runCatching { am.setStreamVolume(AudioManager.STREAM_ALARM, previousVolume, 0) }
            }
        }
        previousVolume = -1
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        current = null
        RingActivity.finishIfOpen()
    }

    override fun onDestroy() {
        stopRinging()
        super.onDestroy()
    }

    // ---- Sound ----

    private fun startSound(alarm: Alarm) {
        val am = getSystemService(AudioManager::class.java)
        audioManager = am
        // Force the alarm stream to the configured level so a muted phone still rings.
        previousVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        val target = (max * alarm.volume / 100).coerceIn(1, max)
        runCatching { am.setStreamVolume(AudioManager.STREAM_ALARM, target, 0) }

        startPlayer(alarm, ramp = alarm.rampUp)
    }

    private fun startPlayer(alarm: Alarm, ramp: Boolean) {
        val uri = alarm.soundUri?.let(Uri::parse)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

        rampActive = ramp
        try {
            // Assigned before configuration so a throwing prepare() can still release it.
            val mp = MediaPlayer()
            player = mp
            mp.apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(this@AlarmService, uri)
                isLooping = true
                if (Build.VERSION.SDK_INT >= 28) {
                    preferredOutputDevice(alarm.output)?.let { setPreferredDevice(it) }
                }
                if (ramp) setVolume(RAMP_START, RAMP_START)
                prepare()
                start()
            }
            if (ramp) rampVolume()
        } catch (e: Exception) {
            Log.e(TAG, "Could not play $uri, vibrating only", e)
            runCatching { player?.release() }
            player = null
            rampActive = false
            startVibration() // last resort: at least wake the user somehow
        }
    }

    /** Steps the player volume from [RAMP_START] to 1.0 over one minute. */
    private fun rampVolume(step: Int = 0) {
        if (!rampActive) return
        val fraction = (RAMP_START + (1f - RAMP_START) * step / RAMP_STEPS).coerceAtMost(1f)
        player?.setVolume(fraction, fraction)
        if (step < RAMP_STEPS) {
            handler.postDelayed({ rampVolume(step + 1) }, RAMP_INTERVAL_MS)
        } else {
            rampActive = false
        }
    }

    // ---- Mission quiet ----

    private fun onMissionActive(alarmId: Long, startId: Int) {
        val alarm = current ?: run { stopSelf(startId); return }
        if (alarm.id != alarmId) return
        val quiet = missionQuiet ?: return
        if (quiet.onActivity()) pauseSound()
        handler.removeCallbacks(lapseRunnable)
        handler.postDelayed(lapseRunnable, quiet.windowMs)
    }

    private fun onMissionLeft(alarmId: Long, startId: Int) {
        val alarm = current ?: run { stopSelf(startId); return }
        if (alarm.id != alarmId || missionQuiet == null) return
        handler.removeCallbacks(lapseRunnable)
        onQuietLapse()
    }

    private fun onQuietLapse() {
        if (missionQuiet?.onLapse() == true) resumeSound()
    }

    /** Mission in progress: silence only the player; stream volume and vibration are untouched. */
    private fun pauseSound() {
        runCatching { player?.pause() }
            .onFailure { Log.w(TAG, "Could not pause alarm sound", it) }
    }

    /** Inactivity: ring again at full volume without restarting the initial ramp. */
    private fun resumeSound() {
        rampActive = false
        val mp = player ?: return // Sound failed at ring time; vibration remains the fallback.
        try {
            mp.setVolume(1f, 1f)
            mp.start()
        } catch (e: Exception) {
            Log.e(TAG, "Could not resume alarm sound; recreating player", e)
            runCatching { mp.release() }
            player = null
            current?.let { startPlayer(it, ramp = false) }
        }
    }

    /** Finds the requested output device, or null to keep the system's default routing. */
    private fun preferredOutputDevice(requested: Output): AudioDeviceInfo? {
        // Per-alarm override wins; DEFAULT falls back to the app-wide setting.
        val output = if (requested == Output.DEFAULT) Prefs(this).defaultOutput else requested
        if (output == Output.AUTO || output == Output.DEFAULT) return null
        val devices = audioManager
            ?.getDevices(AudioManager.GET_DEVICES_OUTPUTS) ?: return null
        val wanted = when (output) {
            Output.SPEAKER -> intArrayOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
            Output.WIRED -> intArrayOf(
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_USB_HEADSET,
            )
            Output.BLUETOOTH -> intArrayOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
            Output.AUTO, Output.DEFAULT -> return null
        }
        return devices.firstOrNull { it.type in wanted }
    }

    // ---- Vibration ----

    private fun startVibration() {
        if (vibrator != null) return // already going
        val v = getSystemService(Vibrator::class.java) ?: return
        vibrator = v
        val pattern = longArrayOf(0, 800, 400, 800, 800)
        v.vibrate(
            VibrationEffect.createWaveform(pattern, 0),
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
        )
    }

    // ---- Plumbing ----

    private fun acquireWakeLock() {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "upupup:ring").apply {
            acquire(AUTO_TIMEOUT_MS + 60_000)
        }
    }

    private fun buildNotification(alarm: Alarm, isTimer: Boolean): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID, getString(R.string.alarm_channel), NotificationManager.IMPORTANCE_HIGH
            ).apply {
                setSound(null, null) // the service plays its own sound
                enableVibration(false)
                setBypassDnd(true)
            }
        )
        val ringIntent = Intent(this, RingActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val fullScreen = PendingIntent.getActivity(
            this, 1, ringIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val title = when {
            isTimer -> getString(R.string.timer_done)
            alarm.label.isNotBlank() -> alarm.label
            else -> getString(R.string.app_name)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle(title)
            .setContentText(getString(R.string.alarm_notification_text))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setFullScreenIntent(fullScreen, true)
            .setContentIntent(fullScreen)
            .build()
    }

    companion object {
        private const val TAG = "AlarmService"
        private const val CHANNEL_ID = "alarm"
        private const val NOTIFICATION_ID = 1
        const val NOTIFICATION_ID_TIMER = 3
        private const val AUTO_TIMEOUT_MS = 5L * 60 * 1000
        private const val RAMP_START = 0.05f
        private const val RAMP_STEPS = 30
        private const val RAMP_INTERVAL_MS = 2_000L

        const val ACTION_SNOOZE = "fr.arichard.upupup.action.SNOOZE"
        const val ACTION_DISMISS = "fr.arichard.upupup.action.DISMISS"
        const val ACTION_MISSION_ACTIVE = "fr.arichard.upupup.action.MISSION_ACTIVE"
        const val ACTION_MISSION_LEFT = "fr.arichard.upupup.action.MISSION_LEFT"
        private const val EXTRA_MISSION_ALARM_ID = "mission_alarm_id"

        /** The alarm currently ringing, readable by [RingActivity]. */
        @Volatile
        var current: Alarm? = null
            private set

        @Volatile
        var currentIsTimer: Boolean = false
            private set

        /** Default settings used when the countdown timer rings. */
        private fun timerAlarm(context: Context) = Alarm(
            id = AlarmScheduler.TIMER_ID,
            hour = 0, minute = 0,
            volume = 80,
            rampUp = false,
            vibrate = true,
            snoozeMinutes = 1, // the "+1 min" button
            mission = Mission.NONE,
            output = TimerStore(context).output,
        )

        /** Whether the currently ringing alarm still has snoozes available. */
        fun canSnooze(context: Context): Boolean {
            val alarm = current ?: return false
            if (alarm.snoozeMinutes <= 0) return false
            if (currentIsTimer) return true
            val max = alarm.maxSnoozes
            return max == 0 || AlarmStore(context).snoozeCount(alarm.id) < max
        }

        fun snooze(context: Context) = command(context, ACTION_SNOOZE)
        fun dismiss(context: Context) = command(context, ACTION_DISMISS)
        fun missionActive(context: Context, alarmId: Long) =
            missionCommand(context, ACTION_MISSION_ACTIVE, alarmId)
        fun missionLeft(context: Context, alarmId: Long) =
            missionCommand(context, ACTION_MISSION_LEFT, alarmId)

        private fun missionCommand(context: Context, action: String, alarmId: Long) {
            if (current == null) return
            runCatching {
                context.startService(
                    Intent(context, AlarmService::class.java).setAction(action)
                        .putExtra(EXTRA_MISSION_ALARM_ID, alarmId)
                )
            }.onFailure { Log.w(TAG, "Mission signal $action not delivered", it) }
        }

        private fun command(context: Context, action: String) {
            context.startService(Intent(context, AlarmService::class.java).setAction(action))
        }
    }
}

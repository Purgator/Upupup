package fr.arichard.upupup

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import fr.arichard.upupup.core.AlarmScheduler
import fr.arichard.upupup.core.AlarmStore
import fr.arichard.upupup.core.Format
import fr.arichard.upupup.core.Prefs
import fr.arichard.upupup.core.TimerStore
import fr.arichard.upupup.core.UpdateManager
import fr.arichard.upupup.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var store: AlarmStore
    private lateinit var timerStore: TimerStore
    private lateinit var adapter: AlarmAdapter
    private val handler = Handler(Looper.getMainLooper())

    private val ticker = object : Runnable {
        override fun run() {
            RingActivity.openIfRinging(this@MainActivity)
            updateNextAlarmBanner()
            updateTimerViews()
            handler.postDelayed(this, 1_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        store = AlarmStore(this)
        timerStore = TimerStore(this)

        adapter = AlarmAdapter(
            onClick = { alarm ->
                startActivity(
                    Intent(this, AlarmEditActivity::class.java)
                        .putExtra(AlarmEditActivity.EXTRA_ID, alarm.id)
                )
            },
            onToggle = { alarm, enabled ->
                val updated = alarm.copy(enabled = enabled)
                store.save(updated)
                if (enabled) store.clearSnooze(alarm.id)
                AlarmScheduler.schedule(this, updated, store)
                refreshAlarms()
                if (enabled) toastNextRing(updated)
            },
            onLongClick = { alarm -> confirmDelete(alarm.id) },
        )
        binding.alarmList.layoutManager = LinearLayoutManager(this)
        binding.alarmList.adapter = adapter

        binding.fabAdd.setOnClickListener {
            startActivity(Intent(this, AlarmEditActivity::class.java))
        }

        binding.tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = showTab(tab.position)
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })

        setupTimerTab()
        requestNeededPermissions()

        // Daily auto-update check, off the main thread.
        Thread { UpdateManager.maybeDailyCheck(this) }.start()
    }

    override fun onResume() {
        super.onResume()
        RingActivity.openIfRinging(this)
        refreshAlarms()
        updateTimerViews()
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    // ---- Alarms tab ----

    private fun refreshAlarms() {
        val alarms = store.all()
        adapter.submit(alarms)
        binding.emptyView.visibility =
            if (alarms.isEmpty() && binding.tabs.selectedTabPosition == 0) View.VISIBLE
            else View.GONE
        updateNextAlarmBanner()
    }

    private fun updateNextAlarmBanner() {
        val now = System.currentTimeMillis()
        val next = store.all()
            .filter { it.enabled }
            .minOfOrNull { alarm ->
                val snoozed = store.snoozeUntil(alarm.id)
                if (snoozed > now) snoozed else alarm.nextTrigger(now)
            }
        binding.nextAlarmBanner.text = if (next == null) {
            getString(R.string.all_alarms_off)
        } else {
            getString(R.string.next_ring_in, Format.delay(this, next - now)) +
                "  ·  " + Format.time(this, next)
        }
    }

    private fun toastNextRing(alarm: fr.arichard.upupup.core.Alarm) {
        val delay = alarm.nextTrigger() - System.currentTimeMillis()
        android.widget.Toast.makeText(
            this, getString(R.string.alarm_set_in, Format.delay(this, delay)),
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    private fun confirmDelete(id: Long) {
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.delete_alarm)
            .setPositiveButton(R.string.delete_alarm) { _, _ ->
                store.delete(id)
                AlarmScheduler.cancel(this, id)
                refreshAlarms()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---- Timer tab ----

    private fun setupTimerTab() {
        binding.pickerHours.minValue = 0
        binding.pickerHours.maxValue = 23
        binding.pickerMinutes.minValue = 0
        binding.pickerMinutes.maxValue = 59
        binding.pickerSeconds.minValue = 0
        binding.pickerSeconds.maxValue = 59

        val last = timerStore.lastDuration
        binding.pickerHours.value = last / 3600
        binding.pickerMinutes.value = last % 3600 / 60
        binding.pickerSeconds.value = last % 60

        binding.timerButton.setOnClickListener {
            if (timerStore.isRunning) cancelTimer() else startTimer()
        }
    }

    private fun startTimer() {
        val seconds = binding.pickerHours.value * 3600 +
            binding.pickerMinutes.value * 60 + binding.pickerSeconds.value
        if (seconds <= 0) return
        timerStore.lastDuration = seconds
        val end = System.currentTimeMillis() + seconds * 1_000L
        timerStore.endTime = end
        AlarmScheduler.scheduleAll(this)
        postTimerNotification(end)
        updateTimerViews()
    }

    private fun cancelTimer() {
        timerStore.clear()
        AlarmScheduler.cancel(this, AlarmScheduler.TIMER_ID)
        getSystemService(NotificationManager::class.java)
            .cancel(fr.arichard.upupup.core.AlarmService.NOTIFICATION_ID_TIMER)
        updateTimerViews()
    }

    private fun updateTimerViews() {
        val running = timerStore.isRunning
        binding.timerPickers.visibility = if (running) View.GONE else View.VISIBLE
        binding.timerCountdown.visibility = if (running) View.VISIBLE else View.GONE
        binding.timerEndAt.visibility = if (running) View.VISIBLE else View.GONE
        binding.timerButton.text =
            getString(if (running) R.string.timer_cancel else R.string.timer_start)
        if (running) {
            val left = (timerStore.endTime - System.currentTimeMillis()).coerceAtLeast(0) / 1000
            binding.timerCountdown.text = String.format(
                java.util.Locale.ROOT, "%02d:%02d:%02d", left / 3600, left % 3600 / 60, left % 60
            )
            binding.timerEndAt.text =
                getString(R.string.timer_notification_text, Format.time(this, timerStore.endTime))
        }
    }

    /** Ongoing countdown notification; the system renders the ticking chronometer. */
    private fun postTimerNotification(end: Long) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                "timer", getString(R.string.timer_running_channel),
                NotificationManager.IMPORTANCE_LOW
            )
        )
        val open = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        manager.notify(
            fr.arichard.upupup.core.AlarmService.NOTIFICATION_ID_TIMER,
            NotificationCompat.Builder(this, "timer")
                .setSmallIcon(R.drawable.ic_alarm)
                .setContentTitle(getString(R.string.timer_notification_title))
                .setContentText(getString(R.string.timer_notification_text, Format.time(this, end)))
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
                .setWhen(end)
                .setOngoing(true)
                .setContentIntent(open)
                .build()
        )
    }

    // ---- Tabs ----

    private fun showTab(position: Int) {
        val alarms = position == 0
        binding.alarmsContent.visibility = if (alarms) View.VISIBLE else View.GONE
        binding.timerContent.visibility = if (alarms) View.GONE else View.VISIBLE
        if (alarms) binding.fabAdd.show() else binding.fabAdd.hide()
        refreshAlarms()
    }

    // ---- Permissions ----

    private fun requestNeededPermissions() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1
            )
        }

        // "Display over other apps": lets the ringing service pop the alarm screen
        // instantly, even from the background or the lock screen. The full-screen
        // notification alone is unreliable on many devices, so insist on this one.
        if (!Settings.canDrawOverlays(this)) {
            MaterialAlertDialogBuilder(this)
                .setMessage(R.string.overlay_needed)
                .setPositiveButton(R.string.grant) { _, _ ->
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:$packageName")
                        )
                    )
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        // Android 12: exact alarms are a user-grantable special permission.
        if (Build.VERSION.SDK_INT in 31..32) {
            val manager = getSystemService(AlarmManager::class.java)
            if (!manager.canScheduleExactAlarms()) {
                MaterialAlertDialogBuilder(this)
                    .setMessage(R.string.exact_alarm_needed)
                    .setPositiveButton(R.string.grant) { _, _ ->
                        startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }

        // Android 14: full-screen intents can be revoked; ask once if missing.
        if (Build.VERSION.SDK_INT >= 34) {
            val prefs = Prefs(this)
            val manager = getSystemService(NotificationManager::class.java)
            if (!manager.canUseFullScreenIntent() && !prefs.fullScreenPromptShown) {
                prefs.fullScreenPromptShown = true
                MaterialAlertDialogBuilder(this)
                    .setMessage(R.string.fullscreen_needed)
                    .setPositiveButton(R.string.grant) { _, _ ->
                        startActivity(
                            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                                .setData(android.net.Uri.parse("package:$packageName"))
                        )
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    // ---- Menu ----

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_settings -> {
            startActivity(Intent(this, SettingsActivity::class.java))
            true
        }
        else -> super.onOptionsItemSelected(item)
    }
}

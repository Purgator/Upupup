package fr.arichard.upupup

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
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
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import fr.arichard.upupup.core.AlarmScheduler
import fr.arichard.upupup.core.AlarmStore
import fr.arichard.upupup.core.Format
import fr.arichard.upupup.core.Output
import fr.arichard.upupup.core.Prefs
import fr.arichard.upupup.core.StopwatchStore
import fr.arichard.upupup.core.TickingNotifications
import fr.arichard.upupup.core.TimerController
import fr.arichard.upupup.core.TimerStore
import fr.arichard.upupup.core.UpdateManager
import fr.arichard.upupup.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var store: AlarmStore
    private lateinit var timerStore: TimerStore
    private lateinit var stopwatchStore: StopwatchStore
    private lateinit var adapter: AlarmAdapter
    private lateinit var lapAdapter: LapAdapter
    private val handler = Handler(Looper.getMainLooper())
    private var selectedTab = R.id.nav_alarms

    private val ticker = object : Runnable {
        override fun run() {
            RingActivity.openIfRinging(this@MainActivity)
            updateNextAlarmBanner()
            updateTimerViews()
            updateStopwatch()
            handler.postDelayed(this, if (stopwatchStore.running) 60 else 1_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        store = AlarmStore(this)
        timerStore = TimerStore(this)
        stopwatchStore = StopwatchStore(this)

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

        binding.bottomNav.setOnItemSelectedListener { item ->
            showTab(item.itemId)
            true
        }

        setupTimerTab()
        setupStopwatchTab()
        requestNeededPermissions()

        // Daily auto-update check, off the main thread. The application context keeps
        // the worker from pinning this activity for the whole network round-trip.
        Thread { UpdateManager.maybeDailyCheck(applicationContext) }.start()
    }

    override fun onResume() {
        super.onResume()
        RingActivity.openIfRinging(this)
        refreshAlarms()
        updateTimerViews()
        updateStopwatch()
        lapAdapter.submit(stopwatchStore.laps())
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    // ---- Alarms tab ----

    private fun refreshAlarms() {
        val alarms = store.all()
        adapter.defaultOutput = Prefs(this).defaultOutput
        adapter.submit(alarms)
        binding.emptyView.visibility =
            if (alarms.isEmpty() && selectedTab == R.id.nav_alarms) View.VISIBLE
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

    /** Seconds the user has composed with the preset buttons, before starting. */
    private var pendingSeconds = 0

    private fun setupTimerTab() {
        // Preset buttons that add to the composed duration.
        val presets = listOf(
            60 to getString(R.string.preset_minutes, 1),
            5 * 60 to getString(R.string.preset_minutes, 5),
            10 * 60 to getString(R.string.preset_minutes, 10),
            15 * 60 to getString(R.string.preset_minutes, 15),
            30 * 60 to getString(R.string.preset_minutes, 30),
            60 * 60 to getString(R.string.preset_hour, 1),
        )
        val density = resources.displayMetrics.density
        presets.forEachIndexed { index, (seconds, label) ->
            val button = com.google.android.material.button.MaterialButton(
                this, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = label
                cornerRadius = (density * 24).toInt()
                layoutParams = android.widget.GridLayout.LayoutParams(
                    android.widget.GridLayout.spec(index / 3, 1f),
                    android.widget.GridLayout.spec(index % 3, 1f)
                ).apply {
                    width = 0
                    setMargins((density * 4).toInt(), (density * 4).toInt(),
                        (density * 4).toInt(), (density * 4).toInt())
                }
                setOnClickListener {
                    pendingSeconds = (pendingSeconds + seconds).coerceAtMost(99 * 3600)
                    updateTimerViews()
                }
            }
            binding.timerPresets.addView(button)
        }

        pendingSeconds = timerStore.lastDuration.coerceIn(0, 99 * 3600)
        binding.timerClear.setOnClickListener {
            if (timerStore.isPaused) {
                TimerController.reset(this)
            } else {
                pendingSeconds = 0
            }
            updateTimerViews()
        }
        binding.timerButton.setOnClickListener {
            when {
                timerStore.isRunning -> cancelTimer()
                timerStore.isPaused -> {
                    TimerController.resume(this)
                    updateTimerViews()
                }
                else -> startTimer()
            }
        }

        // Sound-output selector: tapping an icon is the whole interaction.
        val outputs = mapOf(
            R.id.timer_output_auto to Output.AUTO,
            R.id.timer_output_speaker to Output.SPEAKER,
            R.id.timer_output_wired to Output.WIRED,
            R.id.timer_output_bluetooth to Output.BLUETOOTH,
        )
        binding.timerOutput.check(
            outputs.entries.first { it.value == timerStore.output }.key
        )
        binding.timerOutput.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) outputs[checkedId]?.let { timerStore.output = it }
        }
    }

    private fun startTimer() {
        if (pendingSeconds <= 0) return
        timerStore.lastDuration = pendingSeconds
        timerStore.endTime = System.currentTimeMillis() + pendingSeconds * 1_000L
        AlarmScheduler.scheduleAll(this)
        updateTimerViews()
    }

    private fun cancelTimer() {
        TimerController.reset(this)
        TickingNotifications.cancelTimer(this)
        updateTimerViews()
    }

    private fun updateTimerViews() {
        val running = timerStore.isRunning
        val paused = timerStore.isPaused
        val idle = !running && !paused
        binding.timerCompose.visibility = if (idle) View.VISIBLE else View.GONE
        binding.timerPresets.visibility = if (idle) View.VISIBLE else View.GONE
        binding.timerOutput.visibility = if (idle) View.VISIBLE else View.GONE
        binding.timerClear.visibility =
            if (paused || (idle && pendingSeconds > 0)) View.VISIBLE else View.GONE
        binding.timerClear.text =
            getString(if (paused) R.string.stopwatch_reset else R.string.timer_clear)
        binding.timerCountdown.visibility = if (idle) View.GONE else View.VISIBLE
        binding.timerEndAt.visibility = if (running) View.VISIBLE else View.GONE
        binding.timerButton.text = getString(
            when {
                running -> R.string.timer_cancel
                paused -> R.string.action_resume
                else -> R.string.timer_start
            }
        )
        binding.timerButton.isEnabled = !idle || pendingSeconds > 0

        if (idle) {
            val s = pendingSeconds
            binding.timerCompose.text = String.format(
                java.util.Locale.ROOT, "%02d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60
            )
        } else {
            val leftMs = if (running) timerStore.endTime - System.currentTimeMillis()
            else timerStore.pausedRemaining
            val left = leftMs.coerceAtLeast(0) / 1000
            binding.timerCountdown.text = String.format(
                java.util.Locale.ROOT, "%02d:%02d:%02d", left / 3600, left % 3600 / 60, left % 60
            )
            if (running) {
                binding.timerEndAt.text = getString(
                    R.string.timer_notification_text, Format.time(this, timerStore.endTime)
                )
            }
        }
    }

    // ---- Stopwatch tab ----

    private fun setupStopwatchTab() {
        lapAdapter = LapAdapter()
        binding.lapList.layoutManager = LinearLayoutManager(this)
        binding.lapList.adapter = lapAdapter

        binding.stopwatchToggle.setOnClickListener {
            if (stopwatchStore.running) stopwatchStore.pause() else stopwatchStore.start()
            handler.removeCallbacks(ticker)
            handler.post(ticker)
            updateStopwatch()
        }
        binding.stopwatchLap.setOnClickListener {
            if (stopwatchStore.running) {
                stopwatchStore.addLap()
                lapAdapter.submit(stopwatchStore.laps())
            } else {
                stopwatchStore.reset()
                lapAdapter.submit(emptyList())
                updateStopwatch()
            }
        }
    }

    private fun updateStopwatch() {
        val running = stopwatchStore.running
        binding.stopwatchDisplay.text = Format.stopwatch(stopwatchStore.elapsed())
        binding.stopwatchToggle.text =
            getString(if (running) R.string.stopwatch_pause else R.string.timer_start)
        // While running the secondary button laps; while paused it resets.
        binding.stopwatchLap.text =
            getString(if (running) R.string.stopwatch_lap else R.string.stopwatch_reset)
        binding.stopwatchLap.isEnabled = running || stopwatchStore.elapsed() > 0
    }

    // ---- Tabs ----

    private fun showTab(id: Int) {
        selectedTab = id
        binding.alarmsContent.visibility =
            if (id == R.id.nav_alarms) View.VISIBLE else View.GONE
        binding.timerContent.visibility =
            if (id == R.id.nav_timer) View.VISIBLE else View.GONE
        binding.stopwatchContent.visibility =
            if (id == R.id.nav_stopwatch) View.VISIBLE else View.GONE
        if (id == R.id.nav_alarms) binding.fabAdd.show() else binding.fabAdd.hide()
        if (id == R.id.nav_stopwatch) lapAdapter.submit(stopwatchStore.laps())
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

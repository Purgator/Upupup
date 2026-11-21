package fr.arichard.upupup

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.SimpleItemAnimator
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import fr.arichard.upupup.core.Alarm
import fr.arichard.upupup.core.AlarmScheduler
import fr.arichard.upupup.core.AlarmStore
import fr.arichard.upupup.core.Format
import fr.arichard.upupup.core.Mission
import fr.arichard.upupup.core.Output
import fr.arichard.upupup.core.Prefs
import fr.arichard.upupup.core.ReleaseNotes
import fr.arichard.upupup.core.StopwatchStore
import fr.arichard.upupup.core.SwitchState
import fr.arichard.upupup.core.TickingNotifications
import fr.arichard.upupup.core.TimerController
import fr.arichard.upupup.core.TimerStore
import fr.arichard.upupup.core.UpcomingAlarm
import fr.arichard.upupup.core.UpdateManager
import fr.arichard.upupup.databinding.ActivityMainBinding
import java.util.Calendar

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var store: AlarmStore
    private lateinit var timerStore: TimerStore
    private lateinit var stopwatchStore: StopwatchStore
    private lateinit var adapter: AlarmAdapter
    private lateinit var lapAdapter: LapAdapter
    private val handler = Handler(Looper.getMainLooper())
    private var selectedTab = R.id.nav_alarms

    /** The message currently on screen; replaced, never stacked. */
    private var snackbar: Snackbar? = null

    private val ticker = object : Runnable {
        override fun run() {
            RingActivity.openIfRinging(this@MainActivity)
            updateNextAlarmBanner()
            updateTimerViews()
            updateStopwatch()
            handler.postDelayed(this, if (stopwatchStore.running) 33 else 1_000)
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
            onClick = ::editAlarm,
            onSwitch = ::cycleSwitch,
            menu = ::alarmMenu,
            onUpcomingAction = { alarm, kind ->
                if (kind == AlarmAdapter.Upcoming.SKIPPED) restoreRing(alarm)
                else skipNextRing(alarm)
            },
        )
        binding.alarmList.layoutManager = LinearLayoutManager(this)
        binding.alarmList.adapter = adapter
        // Cards are rebound in place: the switch slides by itself, no cross-fade.
        (binding.alarmList.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false

        binding.fabAdd.setOnClickListener {
            startActivity(Intent(this, AlarmEditActivity::class.java))
        }
        // Long press: a one-shot alarm a few minutes or hours from now.
        binding.fabAdd.setLongPressMenu {
            QUICK_ALARM_MINUTES.map { minutes ->
                MenuAction(getString(R.string.menu_ring_in, durationLabel(minutes))) {
                    quickAlarm(minutes)
                }
            }
        }
        binding.nextAlarmBanner.setLongPressMenu { bannerMenu() }

        binding.bottomNav.setOnItemSelectedListener { item ->
            showTab(item.itemId)
            true
        }

        setupTimerTab()
        setupStopwatchTab()
        setupNavShortcuts()
        showReleaseNotesIfUpdated()
        requestNeededPermissions()

        // Daily auto-update check, off the main thread. The application context keeps
        // the worker from pinning this activity for the whole network round-trip.
        Thread { UpdateManager.maybeDailyCheck(applicationContext) }.start()
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        // The bottom nav restores its own selected item without firing the listener,
        // while the tab containers restart at their XML defaults (alarms visible) —
        // re-sync so the highlighted tab is also the one on screen.
        showTab(binding.bottomNav.selectedItemId)
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
        adapter.submit(alarms, upcomingStates(alarms))
        binding.emptyView.visibility =
            if (alarms.isEmpty() && selectedTab == R.id.nav_alarms) View.VISIBLE
            else View.GONE
        updateNextAlarmBanner(alarms)
    }

    /** Which cards offer to dismiss (or restore) their next ring right now. */
    private fun upcomingStates(alarms: List<Alarm>): Map<Long, AlarmAdapter.UpcomingState> {
        val now = System.currentTimeMillis()
        val states = mutableMapOf<Long, AlarmAdapter.UpcomingState>()
        alarms.filter { it.enabled }.forEach { alarm ->
            val snoozed = store.snoozeUntil(alarm.id)
            val skipped = store.skipUntil(alarm.id)
            val state = when {
                snoozed > now ->
                    AlarmAdapter.UpcomingState(AlarmAdapter.Upcoming.SNOOZED, snoozed)
                alarm.days.isNotEmpty() && skipped > now ->
                    AlarmAdapter.UpcomingState(AlarmAdapter.Upcoming.SKIPPED, skipped)
                alarm.nextTrigger(now) - now <= UpcomingAlarm.CARD_WINDOW_MS ->
                    AlarmAdapter.UpcomingState(AlarmAdapter.Upcoming.SOON)
                else -> null
            }
            if (state != null) states[alarm.id] = state
        }
        return states
    }

    private fun editAlarm(alarm: Alarm) {
        startActivity(
            Intent(this, AlarmEditActivity::class.java)
                .putExtra(AlarmEditActivity.EXTRA_ID, alarm.id)
        )
    }

    private fun switchState(alarm: Alarm): SwitchState {
        val now = System.currentTimeMillis()
        // A pending snooze rings before anything that could have been skipped.
        val skipUntil = if (store.snoozeUntil(alarm.id) > now) 0 else store.skipUntil(alarm.id)
        return SwitchState.of(alarm, now, skipUntil)
    }

    /** One tap on a switch: on → next ring skipped → off → on. */
    private fun cycleSwitch(alarm: Alarm) {
        when (switchState(alarm).afterTap(canSkip = alarm.days.isNotEmpty())) {
            SwitchState.SKIP -> skipNextRing(alarm)
            SwitchState.OFF -> turnOff(alarm)
            SwitchState.ON -> turnOn(alarm)
        }
    }

    /** Enables [alarm] with a clean slate (no snooze, no skipped ring); no UI. */
    private fun enable(alarm: Alarm): Alarm {
        val updated = alarm.copy(enabled = true)
        store.save(updated)
        store.clearSnooze(alarm.id)
        store.clearSkip(alarm.id)
        AlarmScheduler.schedule(this, updated, store)
        return updated
    }

    private fun turnOn(alarm: Alarm) {
        val updated = enable(alarm)
        refreshAlarms()
        noticeNextRing(updated)
    }

    private fun turnOff(alarm: Alarm) {
        UpcomingAlarm.turnOff(this, alarm, store)
        refreshAlarms()
        notice(getString(R.string.alarm_off_notice))
    }

    /**
     * Cancels only the next ring (the half-way switch position) and says which one, with
     * an Undo that puts everything back, a dropped snooze included.
     */
    private fun skipNextRing(alarm: Alarm) {
        val snoozeUntil = store.snoozeUntil(alarm.id)
        val snoozeCount = store.snoozeCount(alarm.id)
        val previousSkip = store.skipUntil(alarm.id)
        val cancelled = UpcomingAlarm.dismissNext(this, alarm, store)
        refreshAlarms()
        if (cancelled == 0L) { // one-shot: nothing left to ring, it is simply off
            notice(getString(R.string.alarm_off_notice))
            return
        }
        val text = getString(
            R.string.skip_notice,
            Format.dayTime(this, cancelled),
            Format.dayTime(this, store.nextRing(alarm)),
        )
        notice(text) {
            val current = store.get(alarm.id)
            if (current != null && current.enabled) {
                val now = System.currentTimeMillis()
                if (snoozeUntil > now) store.restoreSnooze(alarm.id, snoozeUntil, snoozeCount)
                if (previousSkip > now) store.setSkipUntil(alarm.id, previousSkip)
                else store.clearSkip(alarm.id)
                AlarmScheduler.schedule(this, current, store)
                refreshAlarms()
            }
        }
    }

    private fun restoreRing(alarm: Alarm) {
        UpcomingAlarm.restore(this, alarm, store)
        refreshAlarms()
        noticeNextRing(alarm)
    }

    /** Long press on a card or its switch. */
    private fun alarmMenu(alarm: Alarm): List<MenuAction> {
        val actions = mutableListOf<MenuAction>()
        val now = System.currentTimeMillis()
        when (switchState(alarm)) {
            SwitchState.SKIP -> actions += MenuAction(
                getString(R.string.menu_restore_ring, Format.dayTime(this, store.skipUntil(alarm.id)))
            ) { restoreRing(alarm) }
            SwitchState.ON -> if (alarm.days.isNotEmpty()) {
                actions += MenuAction(
                    getString(
                        R.string.menu_skip_ring, Format.dayTime(this, store.nextRing(alarm, now))
                    )
                ) { skipNextRing(alarm) }
            }
            SwitchState.OFF -> Unit
        }
        actions += if (alarm.enabled) {
            MenuAction(getString(R.string.menu_turn_off)) { turnOff(alarm) }
        } else {
            MenuAction(getString(R.string.menu_turn_on)) { turnOn(alarm) }
        }
        if (alarm.mission != Mission.NONE) {
            actions += MenuAction(getString(R.string.menu_try_mission)) {
                startActivity(
                    Intent(this, RingActivity::class.java)
                        .putExtra(RingActivity.EXTRA_PREVIEW_MISSION, alarm.mission.name)
                        .putExtra(RingActivity.EXTRA_PREVIEW_LEVEL, alarm.missionLevel)
                )
            }
        }
        actions += MenuAction(getString(R.string.menu_duplicate)) { duplicate(alarm) }
        actions += MenuAction(getString(R.string.delete_alarm)) { confirmDelete(alarm.id) }
        return actions
    }

    /**
     * The copy starts off and opens in the editor, where saving turns it on: two
     * identical alarms never ring at the same minute by accident.
     */
    private fun duplicate(alarm: Alarm) {
        val copy = alarm.copy(id = System.currentTimeMillis(), enabled = false)
        store.save(copy)
        editAlarm(copy)
    }

    /** Long press on the next-ring banner: act on that ring, or on every alarm at once. */
    private fun bannerMenu(): List<MenuAction> {
        val alarms = store.all()
        val now = System.currentTimeMillis()
        val next = alarms.filter { it.enabled }.minByOrNull { store.nextRing(it, now) }
        val actions = mutableListOf<MenuAction>()
        if (next != null) {
            actions += MenuAction(
                getString(R.string.menu_skip_ring, Format.dayTime(this, store.nextRing(next, now)))
            ) { skipNextRing(next) }
            actions += MenuAction(getString(R.string.edit_alarm)) { editAlarm(next) }
        }
        actions += allAlarmsActions(alarms)
        return actions
    }

    private fun allAlarmsActions(alarms: List<Alarm>): List<MenuAction> {
        val actions = mutableListOf<MenuAction>()
        val on = alarms.filter { it.enabled }
        val off = alarms.filter { !it.enabled }
        if (on.isNotEmpty()) {
            actions += MenuAction(getString(R.string.menu_all_off)) {
                on.forEach { UpcomingAlarm.turnOff(this, it, store) }
                refreshAlarms()
                notice(getString(R.string.all_alarms_off)) {
                    on.forEach { alarm -> store.get(alarm.id)?.let { enable(it) } }
                    refreshAlarms()
                }
            }
        }
        if (off.isNotEmpty()) {
            actions += MenuAction(getString(R.string.menu_all_on)) {
                off.forEach { enable(it) }
                refreshAlarms()
                notice(getString(R.string.all_alarms_on)) {
                    off.forEach { alarm ->
                        store.get(alarm.id)?.let { UpcomingAlarm.turnOff(this, it, store) }
                    }
                    refreshAlarms()
                }
            }
        }
        return actions
    }

    /**
     * A one-shot alarm [minutes] from now (rounded up to a whole minute), set up like
     * the last alarm that was saved.
     */
    private fun quickAlarm(minutes: Int) {
        val at = Calendar.getInstance().apply {
            add(Calendar.MINUTE, minutes)
            if (get(Calendar.SECOND) > 0 || get(Calendar.MILLISECOND) > 0) {
                add(Calendar.MINUTE, 1)
            }
        }
        val template = store.defaultAlarm() ?: Alarm(id = 0, hour = 0, minute = 0)
        val alarm = template.copy(
            id = System.currentTimeMillis(),
            hour = at.get(Calendar.HOUR_OF_DAY),
            minute = at.get(Calendar.MINUTE),
            days = emptySet(),
            label = "",
            enabled = true,
        )
        store.save(alarm)
        AlarmScheduler.schedule(this, alarm, store)
        refreshAlarms()
        noticeNextRing(alarm) {
            store.delete(alarm.id)
            AlarmScheduler.cancel(this, alarm.id)
            refreshAlarms()
        }
    }

    /** "20 min", "8 h": a whole number of hours reads as hours. */
    private fun durationLabel(minutes: Int): String =
        if (minutes % 60 == 0) getString(R.string.hours_short, minutes / 60)
        else getString(R.string.minutes_short, minutes)

    private fun updateNextAlarmBanner(alarms: List<Alarm> = store.all()) {
        val now = System.currentTimeMillis()
        // The ticker gets here every second: let cards enter or leave the "rings soon"
        // window (and lose an elapsed snooze) without waiting for the next resume.
        if (upcomingStates(alarms) != adapter.upcoming) {
            refreshAlarms()
            return
        }
        val next = alarms
            .filter { it.enabled }
            .minOfOrNull { store.nextRing(it, now) }
        binding.nextAlarmBanner.text = if (next == null) {
            getString(R.string.all_alarms_off)
        } else {
            getString(R.string.next_ring_in, Format.delay(this, next - now)) +
                "  ·  " + Format.time(this, next)
        }
    }

    private fun noticeNextRing(alarm: Alarm, undo: (() -> Unit)? = null) {
        val delay = store.nextRing(alarm) - System.currentTimeMillis()
        notice(getString(R.string.alarm_set_in, Format.delay(this, delay)), undo)
    }

    /** Brief message above the bottom bar, replacing the previous one; optional Undo. */
    private fun notice(text: String, undo: (() -> Unit)? = null) {
        snackbar?.dismiss()
        snackbar = Snackbar.make(
            binding.root, text, if (undo != null) Snackbar.LENGTH_LONG else Snackbar.LENGTH_SHORT
        ).apply {
            anchorView = if (selectedTab == R.id.nav_alarms) binding.fabAdd else binding.bottomNav
            if (undo != null) setAction(R.string.undo) { undo() }
            show()
        }
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
                // Long press: start exactly this duration, or take it back off.
                setLongPressMenu {
                    val amount = durationLabel(seconds / 60)
                    val actions = mutableListOf(
                        MenuAction(getString(R.string.menu_start_now, amount)) {
                            pendingSeconds = seconds
                            startTimer()
                        }
                    )
                    if (pendingSeconds >= seconds) {
                        actions += MenuAction(getString(R.string.menu_remove, amount)) {
                            pendingSeconds -= seconds
                            updateTimerViews()
                        }
                    }
                    actions
                }
            }
            binding.timerPresets.addView(button)
        }
        binding.timerCompose.setLongPressMenu {
            val actions = mutableListOf<MenuAction>()
            val last = timerStore.lastDuration.coerceIn(0, 99 * 3600)
            if (last > 0 && last != pendingSeconds) {
                actions += MenuAction(
                    getString(R.string.menu_reuse_last, Format.hms(last.toLong()))
                ) {
                    pendingSeconds = last
                    updateTimerViews()
                }
            }
            if (pendingSeconds > 0) {
                actions += MenuAction(getString(R.string.timer_clear)) {
                    pendingSeconds = 0
                    updateTimerViews()
                }
            }
            actions
        }
        binding.timerCountdown.setLongPressMenu { timerActions() }

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

    /** What can be done to the timer right now; also behind the Timer tab's long press. */
    private fun timerActions(): List<MenuAction> {
        fun plus(minutes: Int) =
            MenuAction(getString(R.string.preset_minutes, minutes)) {
                TimerController.plusMinutes(this, minutes)
                updateTimerViews()
            }
        return when {
            timerStore.isRunning -> listOf(
                plus(1), plus(5),
                MenuAction(getString(R.string.stopwatch_pause)) {
                    TimerController.pause(this)
                    updateTimerViews()
                },
                MenuAction(getString(R.string.timer_cancel)) { cancelTimer() },
            )
            timerStore.isPaused -> listOf(
                MenuAction(getString(R.string.action_resume)) {
                    TimerController.resume(this)
                    updateTimerViews()
                },
                plus(1), plus(5),
                MenuAction(getString(R.string.stopwatch_reset)) { cancelTimer() },
            )
            else -> {
                val last = timerStore.lastDuration.coerceIn(0, 99 * 3600)
                if (last <= 0) emptyList() else listOf(
                    MenuAction(getString(R.string.menu_start_now, Format.hms(last.toLong()))) {
                        pendingSeconds = last
                        startTimer()
                    }
                )
            }
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
            binding.timerCompose.text = Format.hms(pendingSeconds.toLong())
        } else {
            val leftMs = if (running) timerStore.endTime - System.currentTimeMillis()
            else timerStore.pausedRemaining
            binding.timerCountdown.text = Format.hms(leftMs.coerceAtLeast(0) / 1000)
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

        binding.stopwatchToggle.setOnClickListener { toggleStopwatch() }
        binding.stopwatchLap.setOnClickListener {
            if (stopwatchStore.running) {
                stopwatchStore.addLap()
                lapAdapter.submit(stopwatchStore.laps())
            }
        }
        // Reset works even mid-run: one tap does pause + reset.
        binding.stopwatchReset.setOnClickListener { resetStopwatch() }
        binding.stopwatchDisplay.setLongPressMenu {
            val actions = mutableListOf(
                MenuAction(getString(R.string.menu_copy_time)) {
                    copyToClipboard(Format.stopwatch(stopwatchStore.elapsed()))
                }
            )
            if (stopwatchStore.laps().isNotEmpty()) {
                actions += MenuAction(getString(R.string.menu_copy_laps)) {
                    copyToClipboard(LapAdapter.lapsText(this, stopwatchStore.laps()))
                }
            }
            actions
        }
    }

    private fun toggleStopwatch() {
        if (stopwatchStore.running) stopwatchStore.pause() else stopwatchStore.start()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
        updateStopwatch()
    }

    private fun resetStopwatch() {
        stopwatchStore.reset()
        lapAdapter.submit(emptyList())
        updateStopwatch()
    }

    /** Long press on a bottom tab: its most likely action, without opening the tab. */
    private fun setupNavShortcuts() {
        binding.bottomNav.findViewById<View>(R.id.nav_alarms)?.setLongPressMenu {
            listOf(
                MenuAction(getString(R.string.new_alarm)) {
                    startActivity(Intent(this, AlarmEditActivity::class.java))
                }
            ) + allAlarmsActions(store.all())
        }
        binding.bottomNav.findViewById<View>(R.id.nav_timer)?.setLongPressMenu { timerActions() }
        binding.bottomNav.findViewById<View>(R.id.nav_stopwatch)?.setLongPressMenu {
            val actions = mutableListOf(
                MenuAction(
                    getString(
                        if (stopwatchStore.running) R.string.stopwatch_pause
                        else R.string.timer_start
                    )
                ) { toggleStopwatch() }
            )
            if (stopwatchStore.running || stopwatchStore.elapsed() > 0) {
                actions += MenuAction(getString(R.string.stopwatch_reset)) { resetStopwatch() }
            }
            actions
        }
    }

    private fun updateStopwatch() {
        val running = stopwatchStore.running
        binding.stopwatchDisplay.text = Format.stopwatch(stopwatchStore.elapsed())
        binding.stopwatchToggle.text =
            getString(if (running) R.string.stopwatch_pause else R.string.timer_start)
        binding.stopwatchLap.isEnabled = running
        binding.stopwatchReset.isEnabled = running || stopwatchStore.elapsed() > 0
    }

    // ---- Release notes ----

    private fun showReleaseNotesIfUpdated() {
        val notes = ReleaseNotes.pending(this)
        if (notes.isEmpty()) {
            ReleaseNotes.markSeen(this)
            return
        }

        val view = layoutInflater.inflate(R.layout.dialog_release_notes, null)
        val text = SpannableStringBuilder()
        notes.forEachIndexed { index, (version, items) ->
            if (index > 0) text.append("\n\n")
            val headingStart = text.length
            text.append(getString(R.string.version_info, version))
            text.setSpan(
                StyleSpan(Typeface.BOLD),
                headingStart,
                text.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            items.forEach { text.append("\n").append(it) }
        }
        view.findViewById<TextView>(R.id.release_notes_text).text = text
        val checkbox = view.findViewById<MaterialCheckBox>(R.id.release_notes_show_checkbox)
        checkbox.isChecked = true

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.release_notes_title)
            .setView(view)
            .setPositiveButton(android.R.string.ok, null)
            .setOnDismissListener {
                if (!isChangingConfigurations) {
                    Prefs(this).showReleaseNotes = checkbox.isChecked
                    ReleaseNotes.markSeen(this)
                }
            }
            .show()
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

    private companion object {
        /** Offered when the + button is long-pressed. */
        val QUICK_ALARM_MINUTES = listOf(10, 20, 30, 60, 8 * 60)
    }
}

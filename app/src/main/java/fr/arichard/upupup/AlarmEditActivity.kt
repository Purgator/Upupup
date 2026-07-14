package fr.arichard.upupup

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import fr.arichard.upupup.core.Alarm
import fr.arichard.upupup.core.AlarmScheduler
import fr.arichard.upupup.core.AlarmStore
import fr.arichard.upupup.core.Format
import fr.arichard.upupup.core.Mission
import fr.arichard.upupup.core.Output
import fr.arichard.upupup.core.RoutineType
import fr.arichard.upupup.databinding.ActivityAlarmEditBinding
import java.text.DateFormatSymbols
import java.util.Calendar

/** Create/edit screen for one alarm, with all its Alarmy-style options. */
class AlarmEditActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAlarmEditBinding
    private lateinit var store: AlarmStore

    /** The alarm being edited; mutated by the option dialogs, written on Save. */
    private var draft: Alarm = defaultDraft()

    /** Snapshot taken on open, to detect and summarize unsaved changes. */
    private lateinit var original: Alarm
    private var isNew = true

    private val soundPicker =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val uri: Uri? = if (Build.VERSION.SDK_INT >= 33) {
                    result.data?.getParcelableExtra(
                        RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java
                    )
                } else {
                    @Suppress("DEPRECATION")
                    result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
                }
                draft = draft.copy(soundUri = uri?.toString())
                updateValues()
            }
        }

    override fun onResume() {
        super.onResume()
        RingActivity.openIfRinging(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAlarmEditBinding.inflate(layoutInflater)
        setContentView(binding.root)

        store = AlarmStore(this)
        val id = intent.getLongExtra(EXTRA_ID, -1)
        store.get(id)?.let {
            draft = it
            isNew = false
        }
        original = draft

        binding.toolbar.title = getString(if (isNew) R.string.new_alarm else R.string.edit_alarm)
        binding.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        binding.toolbar.setNavigationOnClickListener { confirmExit() }
        onBackPressedDispatcher.addCallback(this) { confirmExit() }
        if (!isNew) {
            binding.toolbar.inflateMenu(R.menu.menu_edit)
            binding.toolbar.setOnMenuItemClickListener {
                store.delete(draft.id)
                AlarmScheduler.cancel(this, draft.id)
                finish()
                true
            }
        }

        setupTimeSelector()

        buildDayToggles()
        binding.labelInput.setText(draft.label)

        binding.rowMission.setOnClickListener { pickMission() }
        binding.rowSound.setOnClickListener { pickSound() }
        binding.rowSnooze.setOnClickListener { pickSnooze() }
        binding.rowOutput.setOnClickListener { pickOutput() }
        binding.rowRoutine.setOnClickListener { pickRoutine() }

        binding.volumeSlider.progress = draft.volume
        binding.volumeSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) draft = draft.copy(volume = progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        binding.rampSwitch.isChecked = draft.rampUp
        binding.rampSwitch.setOnCheckedChangeListener { _, checked ->
            draft = draft.copy(rampUp = checked)
        }
        binding.vibrateSwitch.isChecked = draft.vibrate
        binding.vibrateSwitch.setOnCheckedChangeListener { _, checked ->
            draft = draft.copy(vibrate = checked)
        }

        binding.saveButton.setOnClickListener { save() }
        updateValues()
    }

    /** [draft] plus whatever is currently typed in the label field / wheels. */
    private fun currentDraft(): Alarm {
        // Commit a value typed into a wheel but not yet confirmed (focus still inside).
        if (binding.timeWheels.visibility == View.VISIBLE) {
            binding.wheelHours.clearFocus()
            binding.wheelMinutes.clearFocus()
            draft = draft.copy(
                hour = binding.wheelHours.value, minute = binding.wheelMinutes.value
            )
        }
        return draft.copy(label = binding.labelInput.text?.toString()?.trim().orEmpty())
    }

    private fun save() {
        draft = currentDraft().copy(enabled = true)
        store.save(draft)
        store.clearSnooze(draft.id)
        AlarmScheduler.schedule(this, draft, store)
        val delay = draft.nextTrigger() - System.currentTimeMillis()
        Toast.makeText(
            this, getString(R.string.alarm_set_in, Format.delay(this, delay)), Toast.LENGTH_LONG
        ).show()
        finish()
    }

    // ---- Time selector (wheel or clock, per the app setting) ----

    private fun setupTimeSelector() {
        val wheelMode = fr.arichard.upupup.core.Prefs(this).timePickerMode ==
            fr.arichard.upupup.core.TimePickerMode.WHEEL
        binding.timeWheels.visibility = if (wheelMode) View.VISIBLE else View.GONE
        binding.timeDisplay.visibility = if (wheelMode) View.GONE else View.VISIBLE
        if (wheelMode) setupWheels() else {
            updateTimeDisplay()
            binding.timeDisplay.setOnClickListener { pickTime() }
        }
    }

    private fun setupWheels() {
        val twoDigits = android.widget.NumberPicker.Formatter { String.format(java.util.Locale.ROOT, "%02d", it) }
        binding.wheelHours.apply {
            minValue = 0
            maxValue = 23
            wrapSelectorWheel = true
            setFormatter(twoDigits)
            value = draft.hour
            setOnValueChangedListener { _, _, new ->
                draft = draft.copy(hour = new)
                updateRingsInPreview()
            }
        }
        binding.wheelMinutes.apply {
            minValue = 0
            maxValue = 59
            wrapSelectorWheel = true
            setFormatter(twoDigits)
            value = draft.minute
            setOnValueChangedListener { _, _, new ->
                draft = draft.copy(minute = new)
                updateRingsInPreview()
            }
        }
    }

    // ---- Clock picker ----

    private fun pickTime() {
        val is24h = android.text.format.DateFormat.is24HourFormat(this)
        val picker = com.google.android.material.timepicker.MaterialTimePicker.Builder()
            .setTimeFormat(
                if (is24h) com.google.android.material.timepicker.TimeFormat.CLOCK_24H
                else com.google.android.material.timepicker.TimeFormat.CLOCK_12H
            )
            .setHour(draft.hour)
            .setMinute(draft.minute)
            .setInputMode(
                com.google.android.material.timepicker.MaterialTimePicker.INPUT_MODE_CLOCK
            )
            .setTitleText(R.string.set_time)
            .build()
        picker.addOnPositiveButtonClickListener {
            draft = draft.copy(hour = picker.hour, minute = picker.minute)
            updateTimeDisplay()
            updateRingsInPreview()
        }
        picker.show(supportFragmentManager, "time")
    }

    private fun updateTimeDisplay() {
        binding.timeDisplay.text =
            String.format(java.util.Locale.ROOT, "%02d:%02d", draft.hour, draft.minute)
    }

    // ---- Unsaved-changes guard ----

    /** One localized "Field: old → new" line per difference between open and now. */
    private fun changeSummary(): List<String> {
        val old = original
        val new = currentDraft()
        val lines = mutableListOf<String>()
        fun add(fieldRes: Int, from: String, to: String) {
            lines.add(getString(R.string.change_line, getString(fieldRes), from, to))
        }

        if (old.hour != new.hour || old.minute != new.minute) {
            add(
                R.string.field_time,
                String.format(java.util.Locale.ROOT, "%02d:%02d", old.hour, old.minute),
                String.format(java.util.Locale.ROOT, "%02d:%02d", new.hour, new.minute),
            )
        }
        if (old.days != new.days) {
            add(R.string.repeat, Format.days(this, old.days), Format.days(this, new.days))
        }
        if (old.label != new.label) {
            add(R.string.field_label, old.label.ifBlank { "—" }, new.label.ifBlank { "—" })
        }
        if (old.soundUri != new.soundUri) {
            add(R.string.sound, soundName(old), soundName(new))
        }
        if (old.volume != new.volume) {
            add(R.string.volume, "${old.volume}%", "${new.volume}%")
        }
        if (old.rampUp != new.rampUp) {
            add(R.string.ramp_up, onOff(old.rampUp), onOff(new.rampUp))
        }
        if (old.vibrate != new.vibrate) {
            add(R.string.vibrate, onOff(old.vibrate), onOff(new.vibrate))
        }
        if (old.snoozeMinutes != new.snoozeMinutes || old.maxSnoozes != new.maxSnoozes) {
            add(R.string.snooze, snoozeName(old), snoozeName(new))
        }
        if (old.mission != new.mission || old.missionLevel != new.missionLevel) {
            add(R.string.mission, missionName(old), missionName(new))
        }
        if (old.output != new.output) {
            add(R.string.output_device, outputName(old.output), outputName(new.output))
        }
        if (old.routineType != new.routineType || old.routineValue != new.routineValue) {
            add(R.string.routine, routineName(old), routineName(new))
        }
        return lines
    }

    private fun onOff(value: Boolean): String =
        getString(if (value) R.string.on else R.string.off)

    private fun confirmExit() {
        val changes = changeSummary()
        if (changes.isEmpty()) {
            finish()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.unsaved_title)
            .setMessage(changes.joinToString("\n"))
            .setPositiveButton(R.string.save) { _, _ -> save() }
            .setNegativeButton(R.string.discard) { _, _ -> finish() }
            .setNeutralButton(android.R.string.cancel, null)
            .show()
    }

    // ---- Day toggles ----

    private fun buildDayToggles() {
        val order = listOf(
            Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY,
            Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY
        )
        val symbols = DateFormatSymbols().shortWeekdays
        order.forEach { day ->
            val button = MaterialButton(
                this, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = symbols[day].take(2)
                isCheckable = true
                isChecked = day in draft.days
                insetTop = 0
                insetBottom = 0
                minWidth = 0
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).apply { marginEnd = (resources.displayMetrics.density * 4).toInt() }
                addOnCheckedChangeListener { _, checked ->
                    draft = draft.copy(
                        days = if (checked) draft.days + day else draft.days - day
                    )
                    updateRingsInPreview()
                }
            }
            binding.dayRow.addView(button)
        }
    }

    // ---- Mission picker (bottom sheet with illustrated cards) ----

    private data class MissionChoice(val mission: Mission, val icon: Int, val label: Int)

    private val missionChoices = listOf(
        MissionChoice(Mission.NONE, R.drawable.ic_mission_none, R.string.mission_none),
        MissionChoice(Mission.SHAKE, R.drawable.ic_mission_shake, R.string.mission_shake),
        MissionChoice(Mission.MATH, R.drawable.ic_mission_math, R.string.mission_math),
        MissionChoice(Mission.TYPING, R.drawable.ic_mission_typing, R.string.mission_typing),
        MissionChoice(Mission.STEPS, R.drawable.ic_mission_steps, R.string.mission_steps),
        MissionChoice(Mission.MEMORY, R.drawable.ic_mission_memory, R.string.mission_memory),
    )

    /** Selectable difficulty/amount per mission: value → chip label. */
    private fun levelsFor(mission: Mission): List<Pair<Int, String>> = when (mission) {
        Mission.NONE -> emptyList()
        Mission.SHAKE -> listOf(10, 20, 30, 50, 100).map { it to "$it" }
        Mission.STEPS -> listOf(10, 20, 30, 50).map { it to "$it" }
        Mission.TYPING -> listOf(1, 2, 3).map { it to "$it" }
        Mission.MATH, Mission.MEMORY -> listOf(
            1 to getString(R.string.math_easy),
            2 to getString(R.string.math_medium),
            3 to getString(R.string.math_hard),
        )
    }

    private fun defaultLevelFor(mission: Mission): Int = when (mission) {
        Mission.NONE -> 0
        Mission.SHAKE -> 30
        Mission.STEPS -> 20
        Mission.TYPING -> 1
        Mission.MATH, Mission.MEMORY -> 2
    }

    private fun pickMission() {
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val sheet = fr.arichard.upupup.databinding.DialogMissionPickerBinding
            .inflate(layoutInflater)
        dialog.setContentView(sheet.root)

        var selected = draft.mission
        var level = if (draft.missionLevel > 0) draft.missionLevel else defaultLevelFor(selected)
        val cards = mutableListOf<com.google.android.material.card.MaterialCardView>()

        val accent = com.google.android.material.color.MaterialColors.getColor(
            binding.root, com.google.android.material.R.attr.colorPrimary
        )

        fun refreshCards() {
            cards.forEachIndexed { i, card ->
                val active = missionChoices[i].mission == selected
                card.strokeWidth = (resources.displayMetrics.density * if (active) 2 else 0).toInt()
                card.strokeColor = accent
                card.isChecked = active
            }
        }

        fun refreshLevels() {
            val levels = levelsFor(selected)
            sheet.optionsTitle.visibility =
                if (levels.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
            sheet.levelChips.visibility = sheet.optionsTitle.visibility
            sheet.levelChips.removeAllViews()
            levels.forEach { (value, label) ->
                val chip = com.google.android.material.chip.Chip(
                    this, null,
                    com.google.android.material.R.attr.chipStyle
                ).apply {
                    id = android.view.View.generateViewId() // single-selection needs real ids
                    text = label
                    isCheckable = true
                    isChecked = value == level
                    setOnClickListener { level = value }
                }
                sheet.levelChips.addView(chip)
            }
        }

        val density = resources.displayMetrics.density
        missionChoices.forEachIndexed { index, choice ->
            val card = com.google.android.material.card.MaterialCardView(this).apply {
                radius = density * 14
                isCheckable = true
                layoutParams = android.widget.GridLayout.LayoutParams(
                    android.widget.GridLayout.spec(index / 3, 1f),
                    android.widget.GridLayout.spec(index % 3, 1f)
                ).apply {
                    width = 0
                    setMargins((density * 4).toInt(), (density * 4).toInt(),
                        (density * 4).toInt(), (density * 4).toInt())
                }
            }
            val cell = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                setPadding(0, (density * 14).toInt(), 0, (density * 12).toInt())
            }
            cell.addView(android.widget.ImageView(this).apply {
                setImageResource(choice.icon)
                imageTintList = android.content.res.ColorStateList.valueOf(accent)
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    (density * 34).toInt(), (density * 34).toInt()
                )
            })
            cell.addView(android.widget.TextView(this).apply {
                text = getString(choice.label)
                textSize = 13f
                gravity = android.view.Gravity.CENTER
                setPadding(0, (density * 6).toInt(), 0, 0)
            })
            card.addView(cell)
            card.setOnClickListener {
                selected = choice.mission
                level = if (draft.mission == selected && draft.missionLevel > 0) {
                    draft.missionLevel
                } else {
                    defaultLevelFor(selected)
                }
                refreshCards()
                refreshLevels()
            }
            cards.add(card)
            sheet.missionGrid.addView(card)
        }
        refreshCards()
        refreshLevels()

        sheet.missionTest.setOnClickListener {
            if (selected == Mission.NONE) return@setOnClickListener
            startActivity(
                Intent(this, RingActivity::class.java)
                    .putExtra(RingActivity.EXTRA_PREVIEW_MISSION, selected.name)
                    .putExtra(RingActivity.EXTRA_PREVIEW_LEVEL, level)
            )
        }

        sheet.missionOk.setOnClickListener {
            draft = draft.copy(mission = selected, missionLevel = level)
            if (selected == Mission.STEPS) requestActivityRecognitionIfNeeded()
            updateValues()
            dialog.dismiss()
        }
        dialog.show()
    }

    /** The steps mission needs the activity-recognition runtime permission on Android 10+. */
    private fun requestActivityRecognitionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 29 && checkSelfPermission(
                android.Manifest.permission.ACTIVITY_RECOGNITION
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, R.string.steps_permission, Toast.LENGTH_LONG).show()
            androidx.core.app.ActivityCompat.requestPermissions(
                this, arrayOf(android.Manifest.permission.ACTIVITY_RECOGNITION), 2
            )
        }
    }

    private fun pickSound() {
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, getString(R.string.sound))
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(
                RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI,
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            )
            putExtra(
                RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                draft.soundUri?.let(Uri::parse)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            )
        }
        soundPicker.launch(intent)
    }

    private fun pickSnooze() {
        val durations = intArrayOf(0, 1, 3, 5, 10, 15, 30)
        val labels = durations.map {
            if (it == 0) getString(R.string.snooze_off) else "$it min"
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.snooze_duration)
            .setItems(labels) { _, which ->
                val minutes = durations[which]
                if (minutes == 0) {
                    draft = draft.copy(snoozeMinutes = 0)
                    updateValues()
                } else {
                    pickSnoozeMax(minutes)
                }
            }
            .show()
    }

    private fun pickSnoozeMax(minutes: Int) {
        val maxes = intArrayOf(0, 1, 2, 3, 5)
        val labels = maxes.map {
            if (it == 0) getString(R.string.snooze_unlimited)
            else getString(R.string.snooze_times, it)
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.snooze_max)
            .setItems(labels) { _, which ->
                draft = draft.copy(snoozeMinutes = minutes, maxSnoozes = maxes[which])
                updateValues()
            }
            .show()
    }

    private fun pickOutput() {
        val outputs = arrayOf(Output.DEFAULT, Output.SPEAKER, Output.WIRED, Output.BLUETOOTH)
        val labels = outputs.map { outputName(it) }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.output_device)
            .setSingleChoiceItems(labels, outputs.indexOf(draft.output)) { dialog, which ->
                draft = draft.copy(output = outputs[which])
                updateValues()
                dialog.dismiss()
            }
            .show()
    }

    // ---- Routine (action run after the alarm is stopped) ----

    private fun pickRoutine() {
        val types = arrayOf(
            RoutineType.NONE, RoutineType.APP, RoutineType.SPEAK, RoutineType.ASSISTANT
        )
        val labels = arrayOf(
            getString(R.string.routine_none),
            getString(R.string.routine_type_app),
            getString(R.string.routine_type_speak),
            getString(R.string.routine_type_assistant),
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.routine)
            .setItems(labels) { _, which ->
                when (types[which]) {
                    RoutineType.NONE -> {
                        draft = draft.copy(routineType = RoutineType.NONE, routineValue = null)
                        updateValues()
                    }
                    RoutineType.APP -> pickRoutineApp()
                    RoutineType.SPEAK -> pickRoutineText(RoutineType.SPEAK, R.string.routine_speak_hint)
                    RoutineType.ASSISTANT ->
                        pickRoutineText(RoutineType.ASSISTANT, R.string.routine_assistant_hint)
                }
            }
            .show()
    }

    private fun pickRoutineApp() {
        val pm = packageManager
        val apps = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        )
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != packageName }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.routine_type_app)
            .setItems(apps.map { it.second }.toTypedArray()) { _, which ->
                draft = draft.copy(routineType = RoutineType.APP, routineValue = apps[which].first)
                updateValues()
            }
            .show()
    }

    /** Free-text routine value (phrase to speak, or query to ask the assistant). */
    private fun pickRoutineText(type: RoutineType, hintRes: Int) {
        val input = com.google.android.material.textfield.TextInputEditText(this).apply {
            setHint(hintRes)
            setText(draft.routineValue.takeIf { draft.routineType == type })
            setSingleLine()
        }
        val pad = (resources.displayMetrics.density * 20).toInt()
        val container = android.widget.FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(if (type == RoutineType.SPEAK) R.string.routine_type_speak
                else R.string.routine_type_assistant)
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ ->
                val text = input.text?.toString()?.trim().orEmpty()
                draft = if (text.isEmpty()) {
                    draft.copy(routineType = RoutineType.NONE, routineValue = null)
                } else {
                    draft.copy(routineType = type, routineValue = text)
                }
                updateValues()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun routineName(alarm: Alarm): String = when (alarm.routineType) {
        RoutineType.NONE -> getString(R.string.routine_none)
        RoutineType.APP -> {
            val pkg = alarm.routineValue
            if (pkg == null) {
                getString(R.string.routine_none)
            } else {
                val label = runCatching {
                    packageManager.getApplicationLabel(
                        packageManager.getApplicationInfo(pkg, 0)
                    ).toString()
                }.getOrDefault(pkg)
                getString(R.string.routine_open, label)
            }
        }
        RoutineType.SPEAK -> getString(R.string.routine_speak_desc, alarm.routineValue ?: "")
        RoutineType.ASSISTANT ->
            getString(R.string.routine_assistant_desc, alarm.routineValue ?: "")
    }

    // ---- Display ----

    private fun updateValues() {
        binding.missionValue.text = missionName(draft)
        binding.soundValue.text = soundName(draft)
        binding.snoozeValue.text = snoozeName(draft)
        binding.outputValue.text = outputName(draft.output)
        binding.routineValue.text = routineName(draft)
        updateRingsInPreview()
    }

    private fun missionName(alarm: Alarm): String = when (alarm.mission) {
        Mission.NONE -> getString(R.string.mission_none)
        Mission.SHAKE -> getString(R.string.mission_shake_desc, alarm.missionLevel)
        Mission.MATH -> getString(
            R.string.mission_math_desc, AlarmAdapter.mathLevelName(this, alarm.missionLevel)
        )
        Mission.TYPING -> getString(R.string.mission_typing_desc, alarm.missionLevel)
        Mission.STEPS -> getString(R.string.mission_steps_desc, alarm.missionLevel)
        Mission.MEMORY -> getString(
            R.string.mission_memory_desc, AlarmAdapter.mathLevelName(this, alarm.missionLevel)
        )
    }

    private fun snoozeName(alarm: Alarm): String = if (alarm.snoozeMinutes == 0) {
        getString(R.string.snooze_off)
    } else {
        getString(
            R.string.snooze_desc, alarm.snoozeMinutes,
            if (alarm.maxSnoozes == 0) getString(R.string.snooze_unlimited)
            else getString(R.string.snooze_times, alarm.maxSnoozes)
        )
    }

    private fun updateRingsInPreview() {
        val delay = draft.nextTrigger() - System.currentTimeMillis()
        binding.ringsInPreview.text = getString(R.string.next_ring_in, Format.delay(this, delay))
    }

    private fun soundName(alarm: Alarm): String {
        val uri = alarm.soundUri?.let(Uri::parse) ?: return getString(R.string.sound_default)
        return runCatching { RingtoneManager.getRingtone(this, uri)?.getTitle(this) }
            .getOrNull() ?: getString(R.string.sound_default)
    }

    private fun outputName(output: Output): String = when (output) {
        Output.DEFAULT -> getString(
            R.string.output_default,
            Format.output(this, fr.arichard.upupup.core.Prefs(this).defaultOutput)
        )
        else -> Format.output(this, output)
    }

    private fun defaultDraft(): Alarm {
        val cal = Calendar.getInstance()
        return Alarm(
            id = System.currentTimeMillis(),
            hour = cal.get(Calendar.HOUR_OF_DAY),
            minute = (cal.get(Calendar.MINUTE) + 1) % 60,
        )
    }

    companion object {
        const val EXTRA_ID = "alarm_id"
    }
}

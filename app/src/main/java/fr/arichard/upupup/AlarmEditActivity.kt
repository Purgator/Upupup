package fr.arichard.upupup

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.SeekBar
import android.widget.Toast
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
import fr.arichard.upupup.databinding.ActivityAlarmEditBinding
import java.text.DateFormatSymbols
import java.util.Calendar

/** Create/edit screen for one alarm, with all its Alarmy-style options. */
class AlarmEditActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAlarmEditBinding
    private lateinit var store: AlarmStore

    /** The alarm being edited; mutated by the option dialogs, written on Save. */
    private var draft: Alarm = defaultDraft()
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

        binding.toolbar.title = getString(if (isNew) R.string.new_alarm else R.string.edit_alarm)
        binding.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        binding.toolbar.setNavigationOnClickListener { finish() }
        if (!isNew) {
            binding.toolbar.inflateMenu(R.menu.menu_edit)
            binding.toolbar.setOnMenuItemClickListener {
                store.delete(draft.id)
                AlarmScheduler.cancel(this, draft.id)
                finish()
                true
            }
        }

        binding.timePicker.setIs24HourView(android.text.format.DateFormat.is24HourFormat(this))
        binding.timePicker.hour = draft.hour
        binding.timePicker.minute = draft.minute
        binding.timePicker.setOnTimeChangedListener { _, hour, minute ->
            draft = draft.copy(hour = hour, minute = minute)
            updateRingsInPreview()
        }

        buildDayToggles()
        binding.labelInput.setText(draft.label)

        binding.rowMission.setOnClickListener { pickMission() }
        binding.rowSound.setOnClickListener { pickSound() }
        binding.rowSnooze.setOnClickListener { pickSnooze() }
        binding.rowOutput.setOnClickListener { pickOutput() }

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

    private fun save() {
        draft = draft.copy(
            label = binding.labelInput.text?.toString()?.trim().orEmpty(),
            enabled = true,
        )
        store.save(draft)
        store.clearSnooze(draft.id)
        AlarmScheduler.schedule(this, draft, store)
        val delay = draft.nextTrigger() - System.currentTimeMillis()
        Toast.makeText(
            this, getString(R.string.alarm_set_in, Format.delay(this, delay)), Toast.LENGTH_LONG
        ).show()
        finish()
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

    // ---- Option dialogs ----

    private fun pickMission() {
        val options = arrayOf(
            getString(R.string.mission_none),
            getString(R.string.mission_shake),
            getString(R.string.mission_math),
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.mission)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        draft = draft.copy(mission = Mission.NONE, missionLevel = 0)
                        updateValues()
                    }
                    1 -> pickShakeCount()
                    2 -> pickMathDifficulty()
                }
            }
            .show()
    }

    private fun pickShakeCount() {
        val counts = intArrayOf(10, 20, 30, 50)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.shake_count)
            .setItems(counts.map { it.toString() }.toTypedArray()) { _, which ->
                draft = draft.copy(mission = Mission.SHAKE, missionLevel = counts[which])
                updateValues()
            }
            .show()
    }

    private fun pickMathDifficulty() {
        val labels = arrayOf(
            getString(R.string.math_easy), getString(R.string.math_medium),
            getString(R.string.math_hard)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.math_difficulty)
            .setItems(labels) { _, which ->
                draft = draft.copy(mission = Mission.MATH, missionLevel = which + 1)
                updateValues()
            }
            .show()
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
        val outputs = arrayOf(Output.AUTO, Output.SPEAKER, Output.WIRED, Output.BLUETOOTH)
        val labels = outputs.map { outputName(it) }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.output_device)
            .setItems(labels) { _, which ->
                draft = draft.copy(output = outputs[which])
                updateValues()
            }
            .show()
    }

    // ---- Display ----

    private fun updateValues() {
        binding.missionValue.text = when (draft.mission) {
            Mission.NONE -> getString(R.string.mission_none)
            Mission.SHAKE -> getString(R.string.mission_shake_desc, draft.missionLevel)
            Mission.MATH -> getString(
                R.string.mission_math_desc, AlarmAdapter.mathLevelName(this, draft.missionLevel)
            )
        }
        binding.soundValue.text = soundName()
        binding.snoozeValue.text = if (draft.snoozeMinutes == 0) {
            getString(R.string.snooze_off)
        } else {
            getString(
                R.string.snooze_desc, draft.snoozeMinutes,
                if (draft.maxSnoozes == 0) getString(R.string.snooze_unlimited)
                else getString(R.string.snooze_times, draft.maxSnoozes)
            )
        }
        binding.outputValue.text = outputName(draft.output)
        updateRingsInPreview()
    }

    private fun updateRingsInPreview() {
        val delay = draft.nextTrigger() - System.currentTimeMillis()
        binding.ringsInPreview.text = getString(R.string.next_ring_in, Format.delay(this, delay))
    }

    private fun soundName(): String {
        val uri = draft.soundUri?.let(Uri::parse) ?: return getString(R.string.sound_default)
        return runCatching { RingtoneManager.getRingtone(this, uri)?.getTitle(this) }
            .getOrNull() ?: getString(R.string.sound_default)
    }

    private fun outputName(output: Output): String = when (output) {
        Output.AUTO -> getString(R.string.output_auto)
        Output.SPEAKER -> getString(R.string.output_speaker)
        Output.WIRED -> getString(R.string.output_wired)
        Output.BLUETOOTH -> getString(R.string.output_bluetooth)
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

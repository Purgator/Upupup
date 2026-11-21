package fr.arichard.upupup

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import fr.arichard.upupup.core.Prefs
import fr.arichard.upupup.core.UpdateManager
import fr.arichard.upupup.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    /** Version whose APK is downloaded and waiting behind the Install button. */
    private var readyVersion: String? = null

    override fun onResume() {
        super.onResume()
        RingActivity.openIfRinging(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        binding.toolbar.setNavigationOnClickListener { finish() }

        val prefs = Prefs(this)
        binding.outputValue.text = fr.arichard.upupup.core.Format.output(this, prefs.defaultOutput)
        binding.rowOutput.setOnClickListener {
            val outputs = arrayOf(
                fr.arichard.upupup.core.Output.AUTO,
                fr.arichard.upupup.core.Output.SPEAKER,
                fr.arichard.upupup.core.Output.WIRED,
                fr.arichard.upupup.core.Output.BLUETOOTH,
            )
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.output_device)
                .setSingleChoiceItems(
                    outputs.map { fr.arichard.upupup.core.Format.output(this, it) }
                        .toTypedArray(),
                    outputs.indexOf(prefs.defaultOutput)
                ) { dialog, which ->
                    prefs.defaultOutput = outputs[which]
                    binding.outputValue.text =
                        fr.arichard.upupup.core.Format.output(this, outputs[which])
                    dialog.dismiss()
                }
                .show()
        }

        binding.rowOutput.setLongPressMenu {
            resetAction(prefs.defaultOutput != fr.arichard.upupup.core.Output.AUTO) {
                prefs.defaultOutput = fr.arichard.upupup.core.Output.AUTO
                binding.outputValue.text =
                    fr.arichard.upupup.core.Format.output(this, prefs.defaultOutput)
            }
        }

        setupTimePickerRow(prefs)
        setupPreAlarmRow(prefs)

        binding.swipeSnoozeSwitch.isChecked = prefs.swipeToSnooze
        binding.swipeSnoozeSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.swipeToSnooze = checked
        }
        binding.missionQuietSwitch.isChecked = prefs.missionQuiet
        binding.missionQuietSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.missionQuiet = checked
        }
        binding.autoUpdateSwitch.isChecked = prefs.autoUpdate
        binding.autoUpdateSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.autoUpdate = checked
        }
        binding.betaUpdatesSwitch.isChecked = prefs.betaUpdates
        binding.betaUpdatesSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.betaUpdates = checked
            // Whatever was found or downloaded belongs to the other channel.
            readyVersion = null
            binding.installButton.visibility = android.view.View.GONE
            binding.updateStatus.text = ""
            Thread { UpdateManager.onChannelChanged(applicationContext) }.start()
        }
        binding.releaseNotesSwitch.isChecked = prefs.showReleaseNotes
        binding.releaseNotesSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.showReleaseNotes = checked
        }

        binding.versionText.text =
            getString(R.string.version_info, UpdateManager.currentVersion(this))
        binding.versionText.setLongPressMenu {
            listOf(
                MenuAction(getString(R.string.release_notes_title)) { showAllReleaseNotes() },
                MenuAction(getString(R.string.menu_copy_version)) {
                    copyToClipboard("Upupup " + UpdateManager.currentVersion(this))
                },
                MenuAction(getString(R.string.menu_open_releases)) {
                    runCatching {
                        startActivity(
                            android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(RELEASES_URL)
                            )
                        )
                    }
                },
            )
        }

        // A manual check downloads on ANY network — the Wi-Fi-only rule applies to the
        // automatic daily check, not to the user asking explicitly.
        binding.checkNowButton.setOnClickListener {
            binding.updateStatus.text = getString(R.string.checking)
            binding.checkNowButton.isEnabled = false
            binding.installButton.visibility = android.view.View.GONE
            Thread {
                val result = UpdateManager.check(applicationContext, allowDownload = true) {
                    runOnUiThread {
                        if (isDestroyed) return@runOnUiThread
                        binding.updateStatus.text = getString(R.string.downloading)
                    }
                }
                runOnUiThread { if (!isDestroyed) showCheckResult(result) }
            }.start()
        }

        binding.installButton.setOnClickListener {
            readyVersion?.let { UpdateManager.install(this, it) }
        }
    }

    /** The single "back to default" entry of a settings row, when it has been changed. */
    private fun resetAction(changed: Boolean, reset: () -> Unit): List<MenuAction> =
        if (changed) listOf(MenuAction(getString(R.string.menu_reset_default), reset))
        else emptyList()

    /** The whole bundled release history, newest first. */
    private fun showAllReleaseNotes() {
        val text = fr.arichard.upupup.core.ReleaseNotes.all(this)
            .joinToString("\n\n") { (version, items) ->
                getString(R.string.version_info, version) + "\n" + items.joinToString("\n")
            }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.release_notes_title)
            .setMessage(text)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun setupTimePickerRow(prefs: Prefs) {
        val modes = arrayOf(
            fr.arichard.upupup.core.TimePickerMode.WHEEL,
            fr.arichard.upupup.core.TimePickerMode.CLOCK,
        )
        fun label(mode: fr.arichard.upupup.core.TimePickerMode) = getString(
            if (mode == fr.arichard.upupup.core.TimePickerMode.WHEEL) R.string.time_picker_wheel
            else R.string.time_picker_clock
        )
        binding.timePickerValue.text = label(prefs.timePickerMode)
        binding.rowTimePicker.setLongPressMenu {
            val default = fr.arichard.upupup.core.TimePickerMode.WHEEL
            resetAction(prefs.timePickerMode != default) {
                prefs.timePickerMode = default
                binding.timePickerValue.text = label(default)
            }
        }
        binding.rowTimePicker.setOnClickListener {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.time_picker_style)
                .setSingleChoiceItems(
                    modes.map { label(it) }.toTypedArray(),
                    modes.indexOf(prefs.timePickerMode)
                ) { dialog, which ->
                    prefs.timePickerMode = modes[which]
                    binding.timePickerValue.text = label(modes[which])
                    dialog.dismiss()
                }
                .show()
        }
    }

    private fun setupPreAlarmRow(prefs: Prefs) {
        val choices = intArrayOf(0, 5, 10, 15, 30, 60)
        fun label(minutes: Int) =
            if (minutes == 0) getString(R.string.pre_alarm_off)
            else getString(R.string.pre_alarm_before, minutes)
        binding.preAlarmValue.text = label(prefs.preAlarmMinutes)
        binding.rowPreAlarm.setLongPressMenu {
            resetAction(prefs.preAlarmMinutes != DEFAULT_PRE_ALARM_MINUTES) {
                prefs.preAlarmMinutes = DEFAULT_PRE_ALARM_MINUTES
                binding.preAlarmValue.text = label(DEFAULT_PRE_ALARM_MINUTES)
                fr.arichard.upupup.core.AlarmScheduler.scheduleAll(this)
            }
        }
        binding.rowPreAlarm.setOnClickListener {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.pre_alarm_setting)
                .setSingleChoiceItems(
                    choices.map { label(it) }.toTypedArray(),
                    choices.indexOf(prefs.preAlarmMinutes).coerceAtLeast(0)
                ) { dialog, which ->
                    prefs.preAlarmMinutes = choices[which]
                    binding.preAlarmValue.text = label(choices[which])
                    // Re-arm every alarm so the new lead time takes effect.
                    fr.arichard.upupup.core.AlarmScheduler.scheduleAll(this)
                    dialog.dismiss()
                }
                .show()
        }
    }

    private fun showCheckResult(result: UpdateManager.Result) {
        binding.checkNowButton.isEnabled = true
        binding.updateStatus.text = when (result.status) {
            UpdateManager.Status.UP_TO_DATE ->
                getString(R.string.up_to_date, UpdateManager.currentVersion(this))
            UpdateManager.Status.UPDATE_READY -> {
                readyVersion = result.version
                binding.installButton.visibility = android.view.View.VISIBLE
                getString(R.string.update_downloaded, result.version)
            }
            UpdateManager.Status.UPDATE_DEFERRED ->
                getString(R.string.update_deferred, result.version ?: "?")
            UpdateManager.Status.ERROR ->
                getString(R.string.update_error, result.detail ?: "?")
        }
    }

    private companion object {
        const val RELEASES_URL = "https://github.com/Purgator/Upupup/releases"
        const val DEFAULT_PRE_ALARM_MINUTES = 10
    }
}

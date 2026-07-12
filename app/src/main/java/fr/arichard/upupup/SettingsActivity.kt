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

        binding.swipeSnoozeSwitch.isChecked = prefs.swipeToSnooze
        binding.swipeSnoozeSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.swipeToSnooze = checked
        }
        binding.autoUpdateSwitch.isChecked = prefs.autoUpdate
        binding.autoUpdateSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.autoUpdate = checked
        }

        binding.versionText.text =
            getString(R.string.version_info, UpdateManager.currentVersion(this))

        // A manual check downloads on ANY network — the Wi-Fi-only rule applies to the
        // automatic daily check, not to the user asking explicitly.
        binding.checkNowButton.setOnClickListener {
            binding.updateStatus.text = getString(R.string.checking)
            binding.checkNowButton.isEnabled = false
            binding.installButton.visibility = android.view.View.GONE
            Thread {
                val result = UpdateManager.check(this, allowDownload = true) {
                    runOnUiThread {
                        binding.updateStatus.text = getString(R.string.downloading)
                    }
                }
                runOnUiThread { showCheckResult(result) }
            }.start()
        }

        binding.installButton.setOnClickListener {
            readyVersion?.let { UpdateManager.install(this, it) }
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
}

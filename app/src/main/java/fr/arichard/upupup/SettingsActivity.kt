package fr.arichard.upupup

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import fr.arichard.upupup.core.Prefs
import fr.arichard.upupup.core.UpdateManager
import fr.arichard.upupup.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

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
        binding.autoUpdateSwitch.isChecked = prefs.autoUpdate
        binding.autoUpdateSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.autoUpdate = checked
        }

        binding.versionText.text =
            getString(R.string.version_info, UpdateManager.currentVersion(this))

        binding.checkNowButton.setOnClickListener {
            binding.updateStatus.text = getString(R.string.checking)
            binding.checkNowButton.isEnabled = false
            Thread {
                val result = UpdateManager.check(this, allowDownload = true)
                runOnUiThread {
                    binding.checkNowButton.isEnabled = true
                    binding.updateStatus.text = when (result.status) {
                        UpdateManager.Status.UP_TO_DATE ->
                            getString(R.string.up_to_date, UpdateManager.currentVersion(this))
                        UpdateManager.Status.UPDATE_READY -> {
                            UpdateManager.install(this, result.version!!)
                            getString(R.string.update_ready_title, result.version)
                        }
                        UpdateManager.Status.UPDATE_DEFERRED ->
                            getString(R.string.update_deferred, result.version ?: "?")
                        UpdateManager.Status.ERROR ->
                            getString(R.string.update_error, result.detail ?: "?")
                    }
                }
            }.start()
        }
    }
}

package fr.arichard.upupup

import android.annotation.SuppressLint
import android.hardware.SensorManager
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import fr.arichard.upupup.core.AlarmService
import fr.arichard.upupup.core.Format
import fr.arichard.upupup.core.Mission
import fr.arichard.upupup.databinding.ActivityRingBinding
import fr.arichard.upupup.mission.MathMission
import fr.arichard.upupup.mission.ShakeDetector
import java.lang.ref.WeakReference

/**
 * Full-screen ringing UI, shown over the lock screen. Stopping requires completing
 * the alarm's wake-up mission (shake / math); snoozing is always one tap.
 */
class RingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRingBinding
    private var shakeDetector: ShakeDetector? = null
    private var shakesLeft = 0
    private var mathSolved = 0
    private var mathAnswer = 0
    private var mathInput = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = WeakReference(this)
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        val alarm = AlarmService.current
        if (alarm == null) { // stale launch: the alarm already stopped
            finish()
            return
        }

        binding = ActivityRingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.ringLabel.text = when {
            AlarmService.currentIsTimer -> getString(R.string.timer_done)
            alarm.label.isNotBlank() -> alarm.label
            else -> ""
        }

        binding.stopButton.setOnClickListener { stopAlarm() }

        if (AlarmService.canSnooze(this)) {
            binding.snoozeButton.text = if (AlarmService.currentIsTimer) {
                getString(R.string.plus_one_minute)
            } else {
                getString(R.string.snooze_button, alarm.snoozeMinutes)
            }
            binding.snoozeButton.setOnClickListener {
                val until = System.currentTimeMillis() + alarm.snoozeMinutes * 60_000L
                Toast.makeText(
                    applicationContext,
                    getString(R.string.snoozed_until, Format.time(this, until)),
                    Toast.LENGTH_LONG
                ).show()
                AlarmService.snooze(this)
            }
        } else {
            binding.snoozeButton.visibility = android.view.View.GONE
        }

        when (alarm.mission) {
            Mission.NONE -> Unit
            Mission.SHAKE -> setupShake(alarm.missionLevel.coerceAtLeast(10))
            Mission.MATH -> setupMath(alarm.missionLevel.coerceIn(1, 3))
        }
    }

    private fun stopAlarm() {
        AlarmService.dismiss(this)
        finish()
    }

    // ---- Shake mission ----

    private fun setupShake(count: Int) {
        binding.stopButton.visibility = android.view.View.GONE
        binding.shakeContainer.visibility = android.view.View.VISIBLE
        shakesLeft = count
        binding.shakeProgress.max = count
        binding.shakeProgress.progress = 0
        updateShakeUi()
        shakeDetector = ShakeDetector {
            if (shakesLeft <= 0) return@ShakeDetector
            shakesLeft--
            binding.shakeProgress.progress = binding.shakeProgress.max - shakesLeft
            updateShakeUi()
            if (shakesLeft == 0) stopAlarm()
        }
    }

    private fun updateShakeUi() {
        binding.shakeCount.text = shakesLeft.toString()
    }

    override fun onResume() {
        super.onResume()
        shakeDetector?.start(getSystemService(SensorManager::class.java))
    }

    override fun onPause() {
        shakeDetector?.stop(getSystemService(SensorManager::class.java))
        super.onPause()
    }

    // ---- Math mission ----

    private fun setupMath(difficulty: Int) {
        binding.stopButton.visibility = android.view.View.GONE
        binding.mathContainer.visibility = android.view.View.VISIBLE
        buildKeypad(difficulty)
        nextProblem(difficulty)
    }

    private fun nextProblem(difficulty: Int) {
        val problem = MathMission.generate(difficulty)
        mathAnswer = problem.answer
        mathInput.clear()
        binding.mathProblem.text = problem.text
        binding.mathAnswer.text = ""
        binding.mathStep.text =
            getString(R.string.math_progress, mathSolved + 1, MathMission.PROBLEM_COUNT)
    }

    @SuppressLint("SetTextI18n")
    private fun buildKeypad(difficulty: Int) {
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9",
            getString(R.string.keypad_clear), "0", getString(R.string.keypad_ok))
        keys.forEach { key ->
            val button = Button(this, null, 0,
                com.google.android.material.R.style.Widget_Material3_Button_TextButton).apply {
                text = key
                textSize = 24f
                gravity = Gravity.CENTER
                minWidth = resources.displayMetrics.density.times(88).toInt()
                setTextColor(getColor(android.R.color.white))
                setOnClickListener { onKey(key, difficulty) }
            }
            binding.keypad.addView(button)
        }
    }

    private fun onKey(key: String, difficulty: Int) {
        when (key) {
            getString(R.string.keypad_clear) -> mathInput.clear()
            getString(R.string.keypad_ok) -> {
                if (mathInput.toString() == mathAnswer.toString()) {
                    mathSolved++
                    if (mathSolved >= MathMission.PROBLEM_COUNT) {
                        stopAlarm()
                        return
                    }
                    nextProblem(difficulty)
                } else {
                    mathInput.clear()
                    binding.mathAnswer.text = ""
                    Toast.makeText(this, R.string.math_wrong, Toast.LENGTH_SHORT).show()
                }
                return
            }
            else -> if (mathInput.length < 7) mathInput.append(key)
        }
        binding.mathAnswer.text = mathInput.toString()
    }

    // The only ways out are the mission or the snooze button.
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = Unit

    override fun onDestroy() {
        if (instance?.get() === this) instance = null
        super.onDestroy()
    }

    companion object {
        private var instance: WeakReference<RingActivity>? = null

        /** Called by [AlarmService] when ringing ends for any reason. */
        fun finishIfOpen() {
            instance?.get()?.finish()
        }
    }
}

package fr.arichard.upupup

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import fr.arichard.upupup.core.AlarmService
import fr.arichard.upupup.core.Format
import fr.arichard.upupup.core.Mission
import fr.arichard.upupup.databinding.ActivityRingBinding
import fr.arichard.upupup.mission.MathMission
import fr.arichard.upupup.mission.MemoryMission
import fr.arichard.upupup.mission.ShakeDetector
import fr.arichard.upupup.mission.StepDetector
import java.lang.ref.WeakReference

/**
 * Full-screen ringing UI, shown over the lock screen (or on top of the app when it is
 * open). Stopping requires completing the alarm's wake-up mission; snoozing is always
 * one tap.
 */
class RingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRingBinding
    private val handler = Handler(Looper.getMainLooper())

    private var ringingAlarm: fr.arichard.upupup.core.Alarm? = null
    private var snoozeGestureDetector: android.view.GestureDetector? = null
    private var swipeHelperAnimator: android.animation.ObjectAnimator? = null
    private var isPreview = false

    private var shakeDetector: ShakeDetector? = null
    private var stepDetector: StepDetector? = null
    private var countLeft = 0
    private var countMax = 0

    private var mathSolved = 0
    private var mathAnswer = 0
    private var mathInput = StringBuilder()

    private var phrases: List<String> = emptyList()
    private var phrasesTyped = 0

    private var memorySequence: List<Int> = emptyList()
    private var memoryPosition = 0
    private var memoryInputEnabled = false
    private val memoryTiles = mutableListOf<Button>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = WeakReference(this)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        isPreview = intent.hasExtra(EXTRA_PREVIEW_MISSION)
        if (isPreview) {
            setupPreview()
            return
        }

        // The only ways out are the mission, the snooze or the stop button.
        onBackPressedDispatcher.addCallback(this) { /* consume */ }

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

        ringingAlarm = alarm
        setupSnoozeAction(alarm)
        setupStopAction(alarm)
    }

    /**
     * Mission-less alarms stop with a slide gesture; alarms with a mission show a big
     * button that launches the challenge (completing it is what stops the alarm).
     */
    private fun setupStopAction(alarm: fr.arichard.upupup.core.Alarm) {
        if (alarm.mission == Mission.NONE) {
            binding.stopSliderContainer.visibility = View.VISIBLE
            setupStopSlider()
        } else {
            binding.startMissionButton.visibility = View.VISIBLE
            binding.startMissionButton.setIconResource(missionIcon(alarm.mission))
            binding.startMissionButton.setOnClickListener {
                binding.startMissionButton.visibility = View.GONE
                startMission(alarm.mission, alarm.missionLevel)
                startSensors()
            }
        }
    }

    private fun missionIcon(mission: Mission): Int = when (mission) {
        Mission.SHAKE -> R.drawable.ic_mission_shake
        Mission.MATH -> R.drawable.ic_mission_math
        Mission.TYPING -> R.drawable.ic_mission_typing
        Mission.STEPS -> R.drawable.ic_mission_steps
        Mission.MEMORY, Mission.NONE -> R.drawable.ic_mission_memory
    }

    // ---- Preview: try a mission from the editor, no alarm involved ----

    private fun setupPreview() {
        binding = ActivityRingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.ringLabel.text = getString(R.string.test_mission_running)
        binding.stopSliderContainer.visibility = View.GONE

        val mission = runCatching {
            Mission.valueOf(intent.getStringExtra(EXTRA_PREVIEW_MISSION)!!)
        }.getOrDefault(Mission.NONE)
        val level = intent.getIntExtra(EXTRA_PREVIEW_LEVEL, 1)
        ringingAlarm = fr.arichard.upupup.core.Alarm(
            id = -1, hour = 0, minute = 0, mission = mission, missionLevel = level
        )
        startMission(mission, level)
        // No startSensors() here: onResume() follows immediately and registers them.
    }

    /** A real alarm fired while the preview was open: become the real ring screen. */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (isPreview && AlarmService.current != null) {
            setIntent(intent)
            recreate()
        }
    }

    // ---- Stop: slide gate, then the mission (if any) ----

    private fun setupStopSlider() {
        binding.stopSlider.setOnSeekBarChangeListener(
            object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: android.widget.SeekBar, progress: Int, fromUser: Boolean
                ) = Unit

                override fun onStartTrackingTouch(seekBar: android.widget.SeekBar) = Unit

                override fun onStopTrackingTouch(seekBar: android.widget.SeekBar) {
                    if (seekBar.progress >= 95) onSlideCompleted() else seekBar.progress = 0
                }
            }
        )
        // A slide must start on the handle: ignore taps landing further along the track,
        // otherwise a single tap at the far end would count as a completed slide.
        binding.stopSlider.setOnTouchListener { view, event ->
            event.action == android.view.MotionEvent.ACTION_DOWN &&
                binding.stopSlider.progress < 10 && event.x > view.width * 0.3f
        }
    }

    private fun onSlideCompleted() {
        val alarm = ringingAlarm ?: return
        if (alarm.mission == Mission.NONE) {
            stopAlarm()
            return
        }
        binding.stopSliderContainer.visibility = View.GONE
        startMission(alarm.mission, alarm.missionLevel)
        startSensors()
    }

    private fun startMission(mission: Mission, level: Int) {
        when (mission) {
            Mission.SHAKE -> setupShake(level.coerceAtLeast(10))
            Mission.MATH -> setupMath(level.coerceIn(1, 3))
            Mission.TYPING -> setupTyping(level.coerceIn(1, 3))
            Mission.STEPS -> setupSteps(level.coerceAtLeast(10))
            Mission.MEMORY -> setupMemory(level.coerceIn(1, 3))
            Mission.NONE -> Unit
        }
    }

    // ---- Snooze: swipe up or tap button, per app setting ----

    private fun setupSnoozeAction(alarm: fr.arichard.upupup.core.Alarm) {
        if (!AlarmService.canSnooze(this)) return
        if (fr.arichard.upupup.core.Prefs(this).swipeToSnooze) {
            binding.swipeHelper.visibility = View.VISIBLE
            binding.swipeHint.text = if (AlarmService.currentIsTimer) {
                getString(R.string.swipe_snooze_hint_timer)
            } else {
                getString(R.string.swipe_snooze_hint, alarm.snoozeMinutes)
            }
            animateSwipeHelper()
            snoozeGestureDetector = android.view.GestureDetector(
                this,
                object : android.view.GestureDetector.SimpleOnGestureListener() {
                    override fun onFling(
                        e1: android.view.MotionEvent?, e2: android.view.MotionEvent,
                        velocityX: Float, velocityY: Float
                    ): Boolean {
                        val travelled = (e1?.y ?: e2.y) - e2.y
                        val farEnough = travelled > resources.displayMetrics.density * 120
                        if (velocityY < -2_000 && farEnough) {
                            doSnooze()
                            return true
                        }
                        return false
                    }
                }
            )
        } else {
            binding.snoozeButton.visibility = View.VISIBLE
            binding.snoozeButton.text = if (AlarmService.currentIsTimer) {
                getString(R.string.plus_one_minute)
            } else {
                getString(R.string.snooze_button, alarm.snoozeMinutes)
            }
            binding.snoozeButton.setOnClickListener { doSnooze() }
        }
    }

    /** Looping upward bob of the swipe-up hint so the gesture is obvious. */
    private fun animateSwipeHelper() {
        val distance = resources.displayMetrics.density * 22
        swipeHelperAnimator = android.animation.ObjectAnimator.ofFloat(
            binding.swipeHelper, View.TRANSLATION_Y, 0f, -distance, 0f
        ).apply {
            duration = 1_400
            repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun doSnooze() {
        val alarm = ringingAlarm ?: return
        val until = System.currentTimeMillis() + alarm.snoozeMinutes * 60_000L
        Toast.makeText(
            applicationContext,
            getString(R.string.snoozed_until, Format.time(this, until)),
            Toast.LENGTH_LONG
        ).show()
        AlarmService.snooze(this)
    }

    /** Feeds every touch to the snooze fling detector without stealing it from views. */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        snoozeGestureDetector?.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun stopAlarm() {
        if (isPreview) {
            Toast.makeText(applicationContext, R.string.mission_test_done, Toast.LENGTH_SHORT)
                .show()
        } else {
            val alarm = ringingAlarm
            val isTimer = AlarmService.currentIsTimer
            AlarmService.dismiss(this)
            // Run the after-alarm routine, if one is configured (never for the timer).
            if (alarm != null && !isTimer) {
                fr.arichard.upupup.core.RoutineRunner.run(
                    this, alarm.routineType, alarm.routineValue
                )
            }
        }
        finish()
    }

    // ---- Counter missions: shake & steps ----

    private fun setupShake(count: Int) {
        setupCounter(getString(R.string.shake_instruction), count)
        shakeDetector = ShakeDetector { onCountEvent() }
    }

    private fun setupSteps(count: Int) {
        // No permission or no sensor at ring time must never soften the alarm:
        // fall back to the shake mission with the same count.
        val allowed = Build.VERSION.SDK_INT < 29 || checkSelfPermission(
            android.Manifest.permission.ACTIVITY_RECOGNITION
        ) == PackageManager.PERMISSION_GRANTED
        if (!allowed) {
            setupShake(count)
            return
        }
        setupCounter(getString(R.string.steps_instruction), count)
        stepDetector = StepDetector { onCountEvent() }
    }

    private fun setupCounter(instruction: String, count: Int) {
        binding.counterContainer.visibility = View.VISIBLE
        binding.counterInstruction.text = instruction
        countLeft = count
        countMax = count
        binding.counterValue.text = countLeft.toString()
        updateFill()
    }

    private fun onCountEvent() {
        if (countLeft <= 0) return
        countLeft--
        binding.counterValue.text = countLeft.toString()
        blip() // haptic feedback so the shake/step registers even eyes-closed
        updateFill()
        if (countLeft == 0) stopAlarm()
    }

    /** Grows the colour fill from the bottom in step with the remaining count. */
    private fun updateFill() {
        if (countMax <= 0) return
        val fill = binding.progressFill
        val fraction = (countMax - countLeft).toFloat() / countMax
        val target = (binding.ringRoot.height * fraction).toInt()
        android.animation.ValueAnimator.ofInt(fill.height, target).apply {
            duration = 200
            addUpdateListener {
                fill.layoutParams = fill.layoutParams.apply { height = it.animatedValue as Int }
            }
        }.start()
    }

    /** Short vibration pulse to confirm a shake/step was counted. */
    private fun blip() {
        val vibrator = getSystemService(android.os.Vibrator::class.java) ?: return
        vibrator.vibrate(
            android.os.VibrationEffect.createOneShot(
                45, android.os.VibrationEffect.DEFAULT_AMPLITUDE
            )
        )
    }

    override fun onResume() {
        super.onResume()
        startSensors()
    }

    private fun startSensors() {
        val sensors = getSystemService(SensorManager::class.java)
        shakeDetector?.start(sensors)
        stepDetector?.let {
            // Sensor missing on this device: swap to shake so the mission stays doable.
            if (!it.start(sensors) && countLeft > 0) {
                stepDetector = null
                setupShake(countLeft)
                shakeDetector?.start(sensors)
            }
        }
    }

    override fun onPause() {
        val sensors = getSystemService(SensorManager::class.java)
        shakeDetector?.stop(sensors)
        stepDetector?.stop(sensors)
        super.onPause()
    }

    // ---- Math mission ----

    private fun setupMath(difficulty: Int) {
        binding.mathContainer.visibility = View.VISIBLE
        binding.missionProgress.visibility = View.VISIBLE
        buildKeypad(difficulty)
        nextProblem(difficulty)
    }

    private fun nextProblem(difficulty: Int) {
        val problem = MathMission.generate(difficulty)
        mathAnswer = problem.answer
        mathInput.clear()
        binding.mathProblem.text = problem.text
        binding.mathAnswer.text = ""
        binding.missionProgress.text =
            getString(R.string.step_progress, mathSolved + 1, MathMission.PROBLEM_COUNT)
    }

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

    // ---- Typing mission ----

    private fun setupTyping(phraseCount: Int) {
        binding.typingContainer.visibility = View.VISIBLE
        binding.missionProgress.visibility = View.VISIBLE
        phrases = resources.getStringArray(R.array.typing_phrases)
            .toList().shuffled().take(phraseCount)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        binding.typingInput.requestFocus()
        binding.typingInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable) {
                if (normalize(s.toString()) == normalize(phrases[phrasesTyped])) {
                    phrasesTyped++
                    if (phrasesTyped >= phrases.size) {
                        stopAlarm()
                    } else {
                        s.clear()
                        showPhrase()
                    }
                }
            }

            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        })
        showPhrase()
    }

    private fun showPhrase() {
        binding.typingPhrase.text = phrases[phrasesTyped]
        binding.missionProgress.text =
            getString(R.string.step_progress, phrasesTyped + 1, phrases.size)
    }

    private fun normalize(text: String): String =
        text.trim().replace(Regex("\\s+"), " ").lowercase()

    // ---- Memory mission (Simon-style) ----

    private fun setupMemory(difficulty: Int) {
        binding.memoryContainer.visibility = View.VISIBLE
        memorySequence = MemoryMission.generate(difficulty)

        val size = (resources.displayMetrics.density * 76).toInt()
        val margin = (resources.displayMetrics.density * 5).toInt()
        repeat(MemoryMission.GRID_SIZE) { index ->
            val tile = Button(this).apply {
                layoutParams = android.widget.GridLayout.LayoutParams().apply {
                    width = size
                    height = size
                    setMargins(margin, margin, margin, margin)
                }
                setBackgroundColor(TILE_IDLE)
                setOnClickListener { onTileTapped(index) }
            }
            memoryTiles.add(tile)
            binding.memoryGrid.addView(tile)
        }
        handler.postDelayed({ playSequence() }, 800)
    }

    private fun playSequence() {
        memoryInputEnabled = false
        memoryPosition = 0
        binding.memoryStatus.text = getString(R.string.memory_watch)
        memorySequence.forEachIndexed { i, tile ->
            handler.postDelayed({ flashTile(tile, TILE_SHOW) }, 700L * i + 400)
        }
        handler.postDelayed({
            binding.memoryStatus.text = getString(R.string.memory_repeat)
            memoryInputEnabled = true
        }, 700L * memorySequence.size + 500)
    }

    private fun flashTile(index: Int, color: Int) {
        memoryTiles[index].setBackgroundColor(color)
        handler.postDelayed({ memoryTiles[index].setBackgroundColor(TILE_IDLE) }, 400)
    }

    private fun onTileTapped(index: Int) {
        if (!memoryInputEnabled) return
        if (index == memorySequence[memoryPosition]) {
            flashTile(index, TILE_GOOD)
            memoryPosition++
            if (memoryPosition == memorySequence.size) {
                memoryInputEnabled = false
                stopAlarm()
            }
        } else {
            memoryInputEnabled = false
            flashTile(index, TILE_BAD)
            binding.memoryStatus.text = getString(R.string.memory_wrong)
            handler.postDelayed({ playSequence() }, 1_200)
        }
    }

    // ---- Plumbing ----

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        swipeHelperAnimator?.cancel()
        swipeHelperAnimator = null
        if (instance?.get() === this) instance = null
        super.onDestroy()
    }

    companion object {
        private var instance: WeakReference<RingActivity>? = null

        private val TILE_IDLE = Color.parseColor("#2E3560")
        private val TILE_SHOW = Color.parseColor("#FF8F2E")
        private val TILE_GOOD = Color.parseColor("#4CAF50")
        private val TILE_BAD = Color.parseColor("#E53935")

        const val EXTRA_PREVIEW_MISSION = "preview_mission"
        const val EXTRA_PREVIEW_LEVEL = "preview_level"

        /** A preview doesn't count: a real ring must still be able to take over. */
        val isOpen: Boolean get() = instance?.get()?.isPreview == false

        /** Called by [AlarmService] when ringing ends for any reason. */
        fun finishIfOpen() {
            instance?.get()?.finish()
        }

        /**
         * Brings the ring screen up when an alarm is ringing and the user is looking
         * at the app (the full-screen intent only fires when the screen is off/locked).
         */
        fun openIfRinging(activity: Activity) {
            if (AlarmService.current != null && !isOpen) {
                activity.startActivity(
                    Intent(activity, RingActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }
}

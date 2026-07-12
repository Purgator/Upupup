package fr.arichard.upupup.mission

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * Counts real walking steps and rejects shake-cheating.
 *
 * The hardware step detector happily registers "steps" when the phone is waved or
 * shaken, so the accelerometer is watched at the same time: any step event that occurs
 * near a high-g spike (way above what walking produces) is discarded, and steps faster
 * than a human cadence are ignored. Getting up and walking is the only way through.
 */
class StepDetector(private val onStep: () -> Unit) : SensorEventListener {

    private var lastSpikeAt = 0L
    private var lastStepAt = 0L

    /** Returns false when the device has no step sensor so the caller can fall back. */
    fun start(sensorManager: SensorManager): Boolean {
        val stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) ?: return false
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        return sensorManager.registerListener(this, stepSensor, SensorManager.SENSOR_DELAY_UI)
    }

    fun stop(sensorManager: SensorManager) {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val now = System.currentTimeMillis()
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val (x, y, z) = event.values
                val gForce = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
                if (gForce > SHAKE_SPIKE_G) lastSpikeAt = now
            }
            Sensor.TYPE_STEP_DETECTOR -> {
                val shaken = now - lastSpikeAt < SPIKE_COOLDOWN_MS
                val tooFast = now - lastStepAt < MIN_STEP_INTERVAL_MS
                if (!shaken && !tooFast) {
                    lastStepAt = now
                    onStep()
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        /** Walking peaks around 1.5–2g; shaking the phone goes well beyond this. */
        const val SHAKE_SPIKE_G = 2.4f

        /** Steps reported this close to a shake spike are the shake, not walking. */
        const val SPIKE_COOLDOWN_MS = 1_000L

        /** Nobody walks faster than ~3 steps per second. */
        const val MIN_STEP_INTERVAL_MS = 320L
    }
}

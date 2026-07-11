package fr.arichard.upupup.mission

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * Counts vigorous shakes on the accelerometer using peak detection with hysteresis:
 * one count per hard swing above [TRIGGER_G], and the swing must settle back below
 * [RESET_G] before the next one can count. No time debounce — fast, vigorous shaking
 * counts fast and reliably (one count per physical swing), while gentle wiggling
 * never reaches the trigger threshold at all.
 */
class ShakeDetector(private val onShake: () -> Unit) : SensorEventListener {

    /** Armed = the previous swing has settled; the next strong peak counts. */
    private var armed = true

    fun start(sensorManager: SensorManager) {
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop(sensorManager: SensorManager) {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val (x, y, z) = event.values
        val gForce = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
        if (armed && gForce > TRIGGER_G) {
            armed = false
            onShake()
        } else if (!armed && gForce < RESET_G) {
            armed = true
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        /** A real, energetic shake peaks well above this; a lazy wiggle doesn't. */
        const val TRIGGER_G = 2.8f

        /** Close to rest (1g) — the arm must decelerate before the next count. */
        const val RESET_G = 1.4f
    }
}

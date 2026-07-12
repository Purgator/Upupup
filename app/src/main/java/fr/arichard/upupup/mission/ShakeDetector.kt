package fr.arichard.upupup.mission

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * Counts vigorous shakes on the accelerometer. Three gates must all pass so that one
 * physical shake counts exactly once, and only when it is genuinely strong:
 *
 * 1. Peak threshold: the swing must exceed [TRIGGER_G] — a lazy wiggle never gets there.
 * 2. Hysteresis: the motion must settle below [RESET_G] before the next count can arm,
 *    so one crossing doesn't fire twice on its way up and down.
 * 3. Refractory window: at least [REFRACTORY_MS] between counts — a single back-and-forth
 *    swing crosses the threshold on both the out- and the return-stroke, but only the
 *    first one within the window counts. One full shake = one count.
 */
class ShakeDetector(private val onShake: () -> Unit) : SensorEventListener {

    /** Armed = the previous swing has settled; the next strong peak counts. */
    private var armed = true
    private var lastCountAt = 0L

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
        val now = System.currentTimeMillis()
        if (armed && gForce > TRIGGER_G) {
            armed = false
            if (now - lastCountAt >= REFRACTORY_MS) {
                lastCountAt = now
                onShake()
            }
        } else if (!armed && gForce < RESET_G) {
            armed = true
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        /** Requires a genuinely energetic swing — hold the phone firmly and mean it. */
        const val TRIGGER_G = 3.2f

        /** Near rest (1g): the arm must decelerate before the next count. */
        const val RESET_G = 1.6f

        /** One full back-and-forth shake takes ~400ms; both strokes count as one. */
        const val REFRACTORY_MS = 400L
    }
}

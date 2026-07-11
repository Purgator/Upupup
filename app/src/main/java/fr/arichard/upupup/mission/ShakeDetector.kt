package fr.arichard.upupup.mission

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * Counts vigorous shakes on the accelerometer. A shake is a g-force above
 * [G_THRESHOLD], debounced so one swing isn't counted twice.
 */
class ShakeDetector(private val onShake: () -> Unit) : SensorEventListener {

    private var lastShakeAt = 0L

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
        if (gForce > G_THRESHOLD && now - lastShakeAt > DEBOUNCE_MS) {
            lastShakeAt = now
            onShake()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        const val G_THRESHOLD = 2.2f
        const val DEBOUNCE_MS = 350L
    }
}

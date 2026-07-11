package fr.arichard.upupup.mission

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * Counts steps with the hardware step detector (one event per step).
 * [start] returns false when the device has no step sensor so the caller
 * can fall back to another mission.
 */
class StepDetector(private val onStep: () -> Unit) : SensorEventListener {

    fun start(sensorManager: SensorManager): Boolean {
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) ?: return false
        return sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
    }

    fun stop(sensorManager: SensorManager) {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        repeat(event.values.size.coerceAtLeast(1)) { onStep() }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}

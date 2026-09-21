package com.ma.skydivegps.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

class BarometerTracker(
    context: Context,
    private val onNewReading: (pressureHpa: Float, altitudeMeters: Float) -> Unit
) {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val pressureSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val pressureHpa = event.values[0]
            val altitude = SensorManager.getAltitude(
                SensorManager.PRESSURE_STANDARD_ATMOSPHERE,
                pressureHpa
            )
            onNewReading(pressureHpa, altitude)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    fun hasBarometer(): Boolean = pressureSensor != null

    fun start() {
        pressureSensor?.let {
            sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(listener)
    }
}
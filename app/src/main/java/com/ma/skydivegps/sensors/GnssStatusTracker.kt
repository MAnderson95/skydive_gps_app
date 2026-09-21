package com.ma.skydivegps.sensors

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssStatus
import android.location.LocationManager

class GnssStatusTracker(
    private val context: Context,
    private val onStatusUpdate: (satellitesUsed: Int, avgCn0: Float) -> Unit
) {
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val callback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            var cn0Sum = 0f
            for (i in 0 until status.satelliteCount) {
                if (status.usedInFix(i)) {
                    used++
                    cn0Sum += status.getCn0DbHz(i)
                }
            }
            val avgCn0 = if (used > 0) cn0Sum / used else 0f
            onStatusUpdate(used, avgCn0)
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        locationManager.registerGnssStatusCallback(callback, null)
    }

    fun stop() {
        locationManager.unregisterGnssStatusCallback(callback)
    }
}
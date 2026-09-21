package com.ma.skydivegps.sensors

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import com.google.android.gms.location.*

class LocationTracker(
    private val context: Context,
    private val onNewLocation: (Location) -> Unit
) {
    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            onNewLocation(location)
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            200L
        ).setMinUpdateIntervalMillis(100L)
            .build()

        fusedLocationClient.requestLocationUpdates(
            request,
            locationCallback,
            Looper.getMainLooper()
        )
    }

    fun stop() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }
}
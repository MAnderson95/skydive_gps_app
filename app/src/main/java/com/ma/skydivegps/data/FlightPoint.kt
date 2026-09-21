package com.ma.skydivegps.data

data class FlightPoint(
    val timestamp: Long,            // milliseconds, from the device's monotonic elapsed-realtime clock (NOT wall-clock) —
                                     // stays aligned across GPS and barometer even if the phone's clock adjusts mid-flight
    val latitude: Double,
    val longitude: Double,
    val altitudeGps: Double,        // meters, from GPS
    val altitudeBaro: Float?,       // meters, from barometer (nullable if unavailable)
    val pressureHpa: Float?,        // raw barometric pressure in hPa — lets us reprocess altitude later against a different reference
    val speed: Float,               // meters/second, from GPS (Doppler-derived)
    val speedAccuracy: Float?,      // meters/second, GPS speed measurement uncertainty
    val accuracy: Float,            // GPS horizontal accuracy in meters
    val verticalAccuracy: Float?,   // meters, GPS vertical accuracy
    val bearingAccuracy: Float?,    // degrees, GPS heading accuracy
    val satellitesUsed: Int?,       // satellites used in the most recent GNSS fix
    val avgSignalDb: Float?         // average signal strength (Cn0, dB-Hz) across satellites used
)
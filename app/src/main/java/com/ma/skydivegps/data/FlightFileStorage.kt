package com.ma.skydivegps.data

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FlightFileStorage {

    fun saveFlight(context: Context, points: List<FlightPoint>): File {
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val fileName = "flight_$timestamp.csv"
        val file = File(context.getExternalFilesDir(null), fileName)

        file.bufferedWriter().use { writer ->
            writer.write("timestamp,latitude,longitude,altitudeGps,altitudeBaro,pressureHpa,speed,speedAccuracy,accuracy,verticalAccuracy,bearingAccuracy,satellitesUsed,avgSignalDb")
            writer.newLine()
            for (point in points) {
                writer.write(
                    "${point.timestamp},${point.latitude},${point.longitude}," +
                    "${point.altitudeGps},${point.altitudeBaro ?: ""},${point.pressureHpa ?: ""}," +
                    "${point.speed},${point.speedAccuracy ?: ""}," +
                    "${point.accuracy},${point.verticalAccuracy ?: ""},${point.bearingAccuracy ?: ""}," +
                    "${point.satellitesUsed ?: ""},${point.avgSignalDb ?: ""}"
                )
                writer.newLine()
            }
        }
        return file
    }

    fun listFlights(context: Context): List<File> {
        val dir = context.getExternalFilesDir(null) ?: return emptyList()
        return dir.listFiles { f -> f.name.startsWith("flight_") && f.name.endsWith(".csv") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }
}
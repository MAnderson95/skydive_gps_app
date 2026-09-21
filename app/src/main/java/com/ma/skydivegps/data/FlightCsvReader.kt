package com.ma.skydivegps.data

import java.io.File

object FlightCsvReader {
    /**
     * Parses a flight CSV written by FlightFileStorage back into FlightPoints.
     * Tolerant of empty optional columns (barometer/GNSS fields that weren't
     * available for a given sample).
     */
    fun readFlight(file: File): List<FlightPoint> {
        val lines = file.readLines()
        if (lines.size < 2) return emptyList()

        return lines.drop(1).mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            val cols = line.split(",")
            if (cols.size < 13) return@mapNotNull null
            try {
                FlightPoint(
                    timestamp = cols[0].toLong(),
                    latitude = cols[1].toDouble(),
                    longitude = cols[2].toDouble(),
                    altitudeGps = cols[3].toDouble(),
                    altitudeBaro = cols[4].toFloatOrNull(),
                    pressureHpa = cols[5].toFloatOrNull(),
                    speed = cols[6].toFloat(),
                    speedAccuracy = cols[7].toFloatOrNull(),
                    accuracy = cols[8].toFloat(),
                    verticalAccuracy = cols[9].toFloatOrNull(),
                    bearingAccuracy = cols[10].toFloatOrNull(),
                    satellitesUsed = cols[11].toIntOrNull(),
                    avgSignalDb = cols[12].toFloatOrNull()
                )
            } catch (e: NumberFormatException) {
                null // skip a malformed row rather than fail the whole flight
            }
        }
    }
}
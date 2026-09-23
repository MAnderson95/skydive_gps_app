package com.ma.skydivegps.data

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A recorded flight — a key/identity plus its primary CSV and a human-readable label.
 *  A flight may be backed by more than one file on disk (see filesForFlight below);
 *  this class only carries the primary CSV since that's what the UI displays/opens today. */
data class Flight(
    val key: String,       // e.g. "flight_2026-09-22_14-30-05"
    val csvFile: File,
    val label: String       // e.g. "Sep 22, 2026 - 2:30 PM"
)

object FlightFileStorage {

    // Not thread-safe to share across threads if reused concurrently; each call site here
    // only touches these from a single coroutine/thread at a time, so a shared instance is fine.
    private val KEY_TIMESTAMP_FORMAT = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
    private val LABEL_FORMAT = SimpleDateFormat("MMM d, yyyy - h:mm a", Locale.US)

    fun saveFlight(context: Context, points: List<FlightPoint>, startTimeMillis: Long): File {
        val key = "flight_" + KEY_TIMESTAMP_FORMAT.format(Date(startTimeMillis))
        val file = csvFileForKey(context, key)

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

    /** Same ordering as listFlights(), wrapped with parsed display labels for the picker UI. */
    fun listFlightsAsFlights(context: Context): List<Flight> =
        listFlights(context).map { toFlight(it) }

    fun toFlight(csvFile: File): Flight {
        val key = csvFile.nameWithoutExtension
        return Flight(key = key, csvFile = csvFile, label = labelForKey(key))
    }

    /** Formats a flight's key as a human-readable label. Falls back to the raw key for any
     *  key that doesn't parse (shouldn't happen for files this object wrote, but keeps a
     *  malformed/old file from crashing the list instead of just looking a little ugly). */
    fun labelForKey(key: String): String {
        val tsPart = key.removePrefix("flight_")
        return try {
            KEY_TIMESTAMP_FORMAT.parse(tsPart)?.let { LABEL_FORMAT.format(it) } ?: key
        } catch (e: Exception) {
            key
        }
    }

    fun csvFileForKey(context: Context, key: String): File =
        File(context.getExternalFilesDir(null), "$key.csv")

    /** Every file belonging to one flight, across all known storage locations — today the CSV
     *  plus its cached map image. Add new locations here as new per-flight file types are
     *  introduced (e.g. a future accelerometer companion file) so callers (the picker list and
     *  delete) never need to change when that happens. */
    fun filesForFlight(context: Context, key: String): List<File> {
        val result = mutableListOf<File>()
        csvFileForKey(context, key).takeIf { it.exists() }?.let { result.add(it) }
        MapImageFetcher.cacheFileFor(context, key).takeIf { it.exists() }?.let { result.add(it) }
        return result
    }

    /** Deletes every file belonging to a flight. Returns true only if at least one file existed
     *  and every file that did exist was successfully deleted. */
    fun deleteFlight(context: Context, key: String): Boolean {
        val files = filesForFlight(context, key)
        if (files.isEmpty()) return false
        return files.map { it.delete() }.all { it }
    }
}
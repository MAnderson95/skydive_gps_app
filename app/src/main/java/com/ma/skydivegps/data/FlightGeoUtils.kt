package com.ma.skydivegps.data

import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Converts a recorded flight (raw lat/lon/altitude/time) into the local
 * [x, y, z, t, hSpeed, accuracy] rows the Three.js viewer expects, and works
 * out how to fetch a satellite image that lines up with that same local
 * frame.
 *
 * Convention: the flight's lat/lon bounding-box center is used as BOTH the
 * local coordinate origin (x=0, z=0) AND the satellite image's fetch center,
 * so the fetched image and the flight path are automatically aligned without
 * needing to carry lat/lon any further downstream than this one function.
 */
object FlightGeoUtils {

    private const val METERS_PER_DEGREE_LAT = 111_320.0

    data class FlightPlan(
        val rows: List<DoubleArray>,   // [x, y, z, t, hSpeedMps, accuracyM] per point, in flight order
        val originLat: Double,
        val originLon: Double,
        val mapZoom: Int,
        val mapSizePx: Int,
        val mapWidthMeters: Double,
        val mapHeightMeters: Double
    )

    fun plan(points: List<FlightPoint>, mapSizePx: Int = 640): FlightPlan? {
        if (points.isEmpty()) return null

        var minLat = Double.MAX_VALUE; var maxLat = -Double.MAX_VALUE
        var minLon = Double.MAX_VALUE; var maxLon = -Double.MAX_VALUE
        for (p in points) {
            minLat = minOf(minLat, p.latitude); maxLat = maxOf(maxLat, p.latitude)
            minLon = minOf(minLon, p.longitude); maxLon = maxOf(maxLon, p.longitude)
        }
        val originLat = (minLat + maxLat) / 2.0
        val originLon = (minLon + maxLon) / 2.0
        val latRad = Math.toRadians(originLat)
        val metersPerDegreeLon = METERS_PER_DEGREE_LAT * cos(latRad)

        // ---- local x/y/z rows ----
        val t0 = points.first().timestamp
        val rows = points.map { p ->
            val x = (p.longitude - originLon) * metersPerDegreeLon
            val z = (p.latitude - originLat) * METERS_PER_DEGREE_LAT
            val y = p.altitudeBaro?.toDouble() ?: p.altitudeGps
            val t = (p.timestamp - t0) / 1000.0
            doubleArrayOf(x, y, z, t, p.speed.toDouble(), p.accuracy.toDouble())
        }

        // ---- map zoom/coverage, sized to fit the flight's full extent with margin ----
        val latSpanDeg = max(maxLat - minLat, 0.0005)
        val lonSpanDeg = max(maxLon - minLon, 0.0005)
        val latSpanMeters = latSpanDeg * METERS_PER_DEGREE_LAT
        val lonSpanMeters = lonSpanDeg * metersPerDegreeLon
        val spanMeters = max(max(latSpanMeters, lonSpanMeters), 50.0) // floor: avoid absurd zoom on a near-stationary log
        val margin = 1.3 // keep the path off the image's edge
        val rawZoom = ln(156_543.03392 * cos(latRad) * mapSizePx / (spanMeters * margin)) / ln(2.0)
        val zoom = rawZoom.roundToInt().coerceIn(10, 20)
        val metersPerPixel = 156_543.03392 * cos(latRad) / 2.0.pow(zoom)
        val widthMeters = metersPerPixel * mapSizePx

        return FlightPlan(
            rows = rows,
            originLat = originLat,
            originLon = originLon,
            mapZoom = zoom,
            mapSizePx = mapSizePx,
            mapWidthMeters = widthMeters,
            mapHeightMeters = widthMeters // square image
        )
    }
}
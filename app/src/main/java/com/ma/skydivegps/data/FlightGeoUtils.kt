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
 * Convention: the flight's (post-exit) lat/lon bounding-box center is used as
 * BOTH the local coordinate origin (x=0, z=0) AND the satellite image's fetch
 * center, so the fetched image and the flight path are automatically aligned
 * without needing to carry lat/lon any further downstream than this one
 * function.
 *
 * Axis convention: x = west-positive, y = up (altitude), z = north-positive.
 * This is intentional, not a typo: with y=up and z=north, a right-handed
 * frame (the one Three.js's default camera/rendering pipeline assumes)
 * requires x=west, not x=east. Using x=east here would silently mirror
 * everything drawn on top of this data (confirmed on real flight data
 * 2026-09-21 — turns rendered backwards, satellite image text read
 * backwards, and no camera adjustment could fix it, since a true mirror
 * reflection can't be undone by rotating the viewpoint). Do not "fix" this
 * back to x=east without re-verifying against a real recorded route.
 */
object FlightGeoUtils {

    private const val METERS_PER_DEGREE_LAT = 111_320.0

    // ---- exit-onset detection, ported 2026-09-29 from the standalone script
    // (scratchpad/phase_tune/bbox_fix.py) validated 2026-09-26 against all
    // three real wingsuit flights, which itself mirrors the exit-onset half of
    // classifyPhases() in the Flight Trace viewer's JS (PHASE_CONFIG). Only
    // the exit-onset piece is needed here — the map's bounding box only cares
    // about "where does the real jump start," not the full freefall/canopy
    // split the viewer computes client-side for rendering/camera framing.
    // Deliberately kept numerically identical to the JS/Python versions
    // (same constants, same sustained-first-transition shape) so the map's
    // trim point and the 3D camera's trim point can never drift out of sync
    // with each other.
    private const val FREEFALL_V = -16.0          // m/s, sustained-descent threshold that means "in freefall"
    private const val MIN_SUSTAIN_EXIT = 2.0      // seconds a candidate transition must hold before being confirmed
    private const val MAX_GAP_SECONDS = 3.0       // a real sample gap this long or longer resets any in-progress candidate
    private const val SMOOTH_HALF_SECONDS = 2.5   // vertical-speed smoothing half-window

    /** Smoothed vertical speed at index i: widen left/right from i while still within
     * the half-window duration, then take the altitude delta over the resulting window. */
    private fun smoothedVSpeed(i: Int, t: DoubleArray, alt: DoubleArray): Double {
        var a = i; var b = i
        while (a > 0 && t[i] - t[a - 1] < SMOOTH_HALF_SECONDS) a--
        while (b < t.size - 1 && t[b + 1] - t[i] < SMOOTH_HALF_SECONDS) b++
        val dt = t[b] - t[a]
        return if (dt > 0.15) (alt[b] - alt[a]) / dt else 0.0
    }

    /** Returns the index of the first sustained transition into freefall, or -1 if none
     * is found (e.g. a drive-test log with no real skydive in it) — same "first sustained
     * transition, not single-sample threshold-crossing" shape as the viewer's own detector. */
    private fun findExitOnset(t: DoubleArray, alt: DoubleArray): Int {
        val n = t.size
        val v = DoubleArray(n) { i -> smoothedVSpeed(i, t, alt) }
        var confirmedFreefall = false
        var candidateFreefall: Boolean? = null
        var candStartT = 0.0
        var candStartIdx = 0
        var exitOnset = -1
        for (i in 0 until n) {
            if (i > 0 && (t[i] - t[i - 1]) > MAX_GAP_SECONDS) {
                candidateFreefall = null
            }
            val desired = v[i] < FREEFALL_V
            if (desired == confirmedFreefall) {
                candidateFreefall = null
            } else {
                if (candidateFreefall != desired) {
                    candidateFreefall = desired
                    candStartT = t[i]
                    candStartIdx = i
                } else if (t[i] - candStartT >= MIN_SUSTAIN_EXIT) {
                    confirmedFreefall = desired
                    if (desired && exitOnset == -1) exitOnset = candStartIdx
                    candidateFreefall = null
                }
            }
        }
        return exitOnset
    }

    data class FlightPlan(
        val rows: List<DoubleArray>,   // [x, y, z, t, hSpeedMps, accuracyM] per point, in flight order — full recording, unchanged
        val originLat: Double,
        val originLon: Double,
        val mapZoom: Int,
        val mapSizePx: Int,
        val mapWidthMeters: Double,
        val mapHeightMeters: Double
    )

    fun plan(points: List<FlightPoint>, mapSizePx: Int = 640): FlightPlan? {
        if (points.isEmpty()) return null

        // ---- exit-onset detection (2026-09-29): find where the real jump starts, so the
        // map's fetch area (and the origin shared with it) isn't skewed by the plane's climb
        // to altitude. Falls back to the full recording if no exit is detected (e.g. a
        // drive-test log), same fallback the viewer's own JS phase detection uses.
        val t0 = points.first().timestamp
        val tSeconds = DoubleArray(points.size) { i -> (points[i].timestamp - t0) / 1000.0 }
        val altForPhase = DoubleArray(points.size) { i ->
            points[i].altitudeBaro?.toDouble() ?: points[i].altitudeGps
        }
        val exitIdx = findExitOnset(tSeconds, altForPhase)
        val geoPoints = if (exitIdx >= 0) points.subList(exitIdx, points.size) else points

        var minLat = Double.MAX_VALUE; var maxLat = -Double.MAX_VALUE
        var minLon = Double.MAX_VALUE; var maxLon = -Double.MAX_VALUE
        for (p in geoPoints) {
            minLat = minOf(minLat, p.latitude); maxLat = maxOf(maxLat, p.latitude)
            minLon = minOf(minLon, p.longitude); maxLon = maxOf(maxLon, p.longitude)
        }
        val originLat = (minLat + maxLat) / 2.0
        val originLon = (minLon + maxLon) / 2.0
        val latRad = Math.toRadians(originLat)
        val metersPerDegreeLon = METERS_PER_DEGREE_LAT * cos(latRad)

        // ---- altitude ground-reference baseline (2026-09-30 fix) ----
        // Altitude readouts (the "Altitude" live stat, and the "Freefall — X ft" /
        // "Under Canopy — X ft" legend numbers, all driven by this y value downstream in
        // flight_viewer.html) were showing raw barometric PRESSURE altitude
        // (BarometerTracker.kt uses SensorManager.getAltitude against a fixed 1013.25 hPa
        // standard-atmosphere reference, not that day's real sea-level pressure) with no
        // ground-level correction, even though the UI has always described this stat as
        // "height above the ground at takeoff/landing." The two are only the same on a day
        // where standard pressure happens to match reality — most days they don't. Confirmed
        // 2026-09-30 against Flight 1's real CSV: the very first recorded point (phone still
        // on the ground before takeoff) reads 271.9m/892ft of "altitude" on its own, and
        // subtracting that from the exit/deployment readings lines up exactly with the
        // validated real numbers (13,786ft exit, 2,912ft canopy). Subtracting a constant
        // per-flight baseline here is safe for every OTHER computation that uses these same
        // y values: LandingDetector and the viewer's classifyPhases() only ever look at
        // vertical SPEED (a derivative), which is unaffected by a constant offset, and the
        // camera/map framing only cares about relative extents, not absolute height.
        val groundAltitudeBaseline = points.first().let { it.altitudeBaro?.toDouble() ?: it.altitudeGps }

        // ---- local x/y/z rows — still computed over the FULL recording (not just
        // post-exit), since the viewer's own client-side phase detection needs the
        // pre-exit/plane-ride points to run against, same as it always has. Only the
        // origin they're measured from has changed (post-exit bbox center instead of
        // full-recording bbox center), so the pre-exit points simply land further from
        // (0,0) than before — harmless, since the viewer already discards them for
        // camera-framing purposes once it runs its own phase detection.
        val rows = points.map { p ->
            val x = (originLon - p.longitude) * metersPerDegreeLon  // west-positive — see axis convention note above
            val z = (p.latitude - originLat) * METERS_PER_DEGREE_LAT
            val y = (p.altitudeBaro?.toDouble() ?: p.altitudeGps) - groundAltitudeBaseline
            val t = (p.timestamp - t0) / 1000.0
            doubleArrayOf(x, y, z, t, p.speed.toDouble(), p.accuracy.toDouble())
        }

        // ---- map zoom/coverage, sized to fit the (post-exit) extent with margin ----
        val latSpanDeg = max(maxLat - minLat, 0.0005)
        val lonSpanDeg = max(maxLon - minLon, 0.0005)
        val latSpanMeters = latSpanDeg * METERS_PER_DEGREE_LAT
        val lonSpanMeters = lonSpanDeg * metersPerDegreeLon
        val spanMeters = max(max(latSpanMeters, lonSpanMeters), 50.0) // floor: avoid absurd zoom on a near-stationary log
        // 1.3 = the validated standalone script's 15%-per-side padding, expressed as a
        // single multiplier (1 + 2*0.15 = 1.3) — keeps the path off the image's edge.
        val margin = 1.3
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

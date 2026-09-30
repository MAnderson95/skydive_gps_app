package com.ma.skydivegps.data

import kotlin.math.abs

/**
 * On-device port of the Flight Trace viewer's `classifyPhases()`/`detectLanding()` (JS),
 * built 2026-09-29 for the real Kotlin recorder-side auto-stop (Roadmap item 10). Deliberately
 * kept numerically identical to the viewer's validated logic (same constants, same "first
 * sustained transition, not single-sample threshold-crossing" shape) — this runs against live,
 * still-recording data rather than a finished CSV, but the underlying physics and thresholds are
 * the same ones already validated against all three real 2026-09-26 flights.
 *
 * Two genuinely separate uses of this same detector, per the standing project decision:
 * - The **viewer** (JS, already shipped) can be comparatively aggressive (6s sustain) because a
 *   false positive there is purely cosmetic — nothing is deleted, it just clips where playback
 *   stops.
 * - The **recorder** (this file) has to be conservative — a false "landed" call here permanently
 *   loses the rest of a real flight — so it uses a much longer sustain requirement
 *   ([RECORDER_SUSTAIN_SECONDS]) plus an extra safety buffer stacked on top
 *   ([RECORDER_BUFFER_SECONDS]) before the recording is actually cut, giving real time for
 *   post-landing housekeeping (securing the canopy, camera, walking) without it counting against
 *   the sustain check.
 *
 * Deliberately keys off barometric altitude, never GPS `speed` — GPS speed is confirmed unreliable
 * right after touchdown (a real flight's `speed` field got stuck reporting a bogus ~330mph for 48s
 * straight after the phone was already sitting still on the ground; see PROJECT_CONTEXT item 10's
 * data-quality findings).
 */
object LandingDetector {

    private const val FREEFALL_VSPEED_MPS = -16.0
    private const val CANOPY_VSPEED_MAX_MPS = 2.0
    private const val MIN_SUSTAIN_SECONDS = 2.0
    private const val MIN_SUSTAIN_DEPLOY_SECONDS = 10.0
    private const val MAX_SAMPLE_GAP_SECONDS = 3.0
    private const val SMOOTH_HALF_WINDOW_SECONDS = 2.5

    /** "Stationary" vertical-speed band, +/- this many m/s. */
    const val LANDING_VSPEED_BAND_MPS = 1.5

    /** How long smoothed vertical speed must stay inside the stationary band before the
     * conservative recorder-side logic will even call this "landed." */
    const val RECORDER_SUSTAIN_SECONDS = 25.0

    /** Extra margin (seconds) stacked on top of the sustain check above, before the recording is
     * actually cut — gives real post-landing housekeeping time. (Bumped 75->90 during viewer
     * validation: 75s only cleared one real flight's actual manual-stop time by ~15s, a thin
     * margin; 90s gives comfortable headroom above the worst observed real housekeeping time,
     * ~58s.) */
    const val RECORDER_BUFFER_SECONDS = 90.0

    data class PhaseResult(
        val hasExit: Boolean,
        val exitOnsetIdx: Int,
        val hasDeployment: Boolean,
        val deployOnsetIdx: Int
    )

    private fun altitudeOf(p: FlightPoint): Double = p.altitudeBaro?.toDouble() ?: p.altitudeGps

    /** Vertical speed, smoothed over a time-based window (robust to variable sample rate) —
     * mirrors the viewer's `smoothedVSpeedAt`. */
    private fun smoothedVSpeed(i: Int, t: DoubleArray, alt: DoubleArray): Double {
        var a = i; var b = i
        while (a > 0 && t[i] - t[a - 1] < SMOOTH_HALF_WINDOW_SECONDS) a--
        while (b < t.size - 1 && t[b + 1] - t[i] < SMOOTH_HALF_WINDOW_SECONDS) b++
        val dt = t[b] - t[a]
        return if (dt > 0.15) (alt[b] - alt[a]) / dt else 0.0
    }

    /** Full exit + first-deployment classification, mirroring the viewer's `classifyPhases()`.
     * Safe (and cheap enough) to call repeatedly on a growing points list as a flight records —
     * see the recorder-side caller for why re-scanning from scratch each time is fine here. */
    fun classifyPhases(points: List<FlightPoint>): PhaseResult {
        val n = points.size
        if (n == 0) return PhaseResult(hasExit = false, exitOnsetIdx = -1, hasDeployment = false, deployOnsetIdx = -1)

        val t0 = points.first().timestamp
        val t = DoubleArray(n) { i -> (points[i].timestamp - t0) / 1000.0 }
        val alt = DoubleArray(n) { i -> altitudeOf(points[i]) }
        val v = DoubleArray(n) { i -> smoothedVSpeed(i, t, alt) }

        var confirmed = "PLANE"
        var haveSeenFreefall = false
        var candidate: String? = null
        var candidateStartT = 0.0
        var candidateStartIdx = 0
        var exitOnsetIdx = -1
        var deployOnsetIdx = -1

        for (i in 0 until n) {
            // a real gap in samples can't itself count as evidence a candidate phase held
            // steady, so don't let it bridge a sustain check
            if (i > 0 && (t[i] - t[i - 1]) > MAX_SAMPLE_GAP_SECONDS) {
                candidate = null
            }

            val vi = v[i]
            val rawBand = when {
                vi < FREEFALL_VSPEED_MPS -> "FREEFALL"
                vi <= CANOPY_VSPEED_MAX_MPS -> "MID"
                else -> "PLANE"
            }
            val desired = when (rawBand) {
                "FREEFALL" -> "FREEFALL"
                "MID" -> if (haveSeenFreefall) "CANOPY" else "PLANE"
                else -> "PLANE"
            }

            // while hunting for the first real deployment, treat ANY move away from FREEFALL as
            // suspect and require the longer sustain window (see class doc for why)
            val awaitingDeploy = haveSeenFreefall && deployOnsetIdx == -1
            val requiredSustain = if (awaitingDeploy && desired != "FREEFALL") {
                MIN_SUSTAIN_DEPLOY_SECONDS
            } else {
                MIN_SUSTAIN_SECONDS
            }

            if (desired == confirmed) {
                candidate = null
            } else {
                if (candidate != desired) {
                    candidate = desired
                    candidateStartT = t[i]
                    candidateStartIdx = i
                } else if (t[i] - candidateStartT >= requiredSustain) {
                    confirmed = desired
                    if (desired == "FREEFALL" && exitOnsetIdx == -1) {
                        haveSeenFreefall = true
                        exitOnsetIdx = candidateStartIdx
                    }
                    if (desired == "CANOPY" && deployOnsetIdx == -1) {
                        deployOnsetIdx = candidateStartIdx
                    }
                    candidate = null
                }
            }
        }

        return PhaseResult(
            hasExit = exitOnsetIdx != -1,
            exitOnsetIdx = exitOnsetIdx,
            hasDeployment = deployOnsetIdx != -1,
            deployOnsetIdx = deployOnsetIdx
        )
    }

    /** First index (>= fromIdx) where smoothed vertical speed stays inside the stationary band
     * for at least sustainSeconds — same "first sustained transition" shape as [classifyPhases],
     * not a single-sample threshold crossing. Mirrors the viewer's `detectLanding()`. Callers
     * should only invoke this once deployment has been confirmed ([PhaseResult.hasDeployment]),
     * same as the viewer — otherwise a stationary period before takeoff (taxiing, waiting on the
     * ground) could satisfy the sustain check on its own. */
    fun detectLanding(points: List<FlightPoint>, fromIdx: Int, sustainSeconds: Double): Int {
        if (fromIdx < 0 || points.isEmpty()) return -1

        val t0 = points.first().timestamp
        val t = DoubleArray(points.size) { i -> (points[i].timestamp - t0) / 1000.0 }
        val alt = DoubleArray(points.size) { i -> altitudeOf(points[i]) }

        var candidateStartIdx = -1
        var candidateStartT = 0.0
        for (i in fromIdx until points.size) {
            if (i > fromIdx && (t[i] - t[i - 1]) > MAX_SAMPLE_GAP_SECONDS) {
                candidateStartIdx = -1
            }
            val stationary = abs(smoothedVSpeed(i, t, alt)) <= LANDING_VSPEED_BAND_MPS
            if (stationary) {
                if (candidateStartIdx == -1) {
                    candidateStartIdx = i
                    candidateStartT = t[i]
                } else if (t[i] - candidateStartT >= sustainSeconds) {
                    return candidateStartIdx
                }
            } else {
                candidateStartIdx = -1
            }
        }
        return -1
    }
}

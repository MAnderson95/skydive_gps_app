package com.ma.skydivegps.data

/**
 * Maps a live GPS signal-strength reading (Cn0, dB-Hz — the real per-satellite carrier-to-noise
 * ratio, not just how many satellites are in the fix) to a glanceable 4-tier label. Replaces the
 * raw satellite-count placeholder previously shown on the Home screen's Recording-active card and
 * in the foreground/lock-screen notification.
 *
 * Thresholds are derived from the real 2026-09-26 flight data (see PROJECT_CONTEXT's Roadmap item
 * 1 findings): avgSignalDb ran roughly 23-25 dB-Hz median during the bulk of a flight, climbing to
 * 28-36 dB-Hz near landing. Weak/OK boundary set below that whole observed range (a real problem
 * signal, not just "mid-flight normal"); OK/Solid and Solid/Strong split the rest so all three
 * flights' bulk-of-flight readings land in OK/Solid and their near-landing readings reach Strong.
 */
object SignalStrength {
    enum class Tier(val label: String) {
        WEAK("Weak"),
        OK("OK"),
        SOLID("Solid"),
        STRONG("Strong")
    }

    fun tierFor(avgSignalDb: Float): Tier = when {
        avgSignalDb < 20f -> Tier.WEAK
        avgSignalDb < 25f -> Tier.OK
        avgSignalDb < 30f -> Tier.SOLID
        else -> Tier.STRONG
    }
}

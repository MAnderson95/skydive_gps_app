package com.ma.skydivegps.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.ma.skydivegps.R

/**
 * IBM Plex Mono, used throughout the prototype for anything numeric/tabular (chronometer,
 * playback time, stat values, speed pill) — same font family referenced as
 * `'IBM Plex Mono',monospace` in the ported `flight_viewer.html`'s CSS. Bundled as static .ttf
 * files (SIL Open Font License) under res/font rather than pulled via Google Fonts' downloadable
 * font provider, to avoid a Play Services dependency for four small files.
 */
val IBMPlexMono = FontFamily(
    Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_mono_medium, FontWeight.Medium),
    Font(R.font.ibm_plex_mono_semibold, FontWeight.SemiBold),
    Font(R.font.ibm_plex_mono_bold, FontWeight.Bold)
)

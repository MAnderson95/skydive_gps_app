package com.ma.skydivegps.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The "Tailwind" rebrand palette, taken verbatim from the Design thread's clickable prototype
 * (`https://claude.ai/artifact/XZHTaWyTAFbrcVC7HrDAgb`) — same colors already ported into the
 * app's bundled `flight_viewer.html` (Phase 2 commit 1). Named for where each one shows up in
 * the prototype rather than an abstract scale, since that's the thing a future edit will need
 * to look up against.
 */
object TailwindColors {
    /** Splash/home background gradient's deepest stop; also the Record Flight card's fill. */
    val NavyDeep = Color(0xFF062043)

    /** Primary navy — gradient mid-stop, Past Flights header text/back icon, the most-recent
     *  flight's card in the Past Flights list, "Stop Recording"/"Open in 3D Viewer" button text. */
    val Navy = Color(0xFF0C3D78)

    /** Gradient upper-mid stop, and the sky gradient's matching stop in the 3D viewer. */
    val BlueMid = Color(0xFF2F7FCE)

    /** Gradient's lightest stop; also the Past Flights screen's solid background. */
    val SkyLight = Color(0xFFBFE0F5)

    /** "View Latest Flight" card fill. */
    val BlueCard = Color(0xFF16467C)

    /** Past Flights list card fill, for every flight except the most recent (which uses Navy). */
    val BlueCardAlt = Color(0xFF2C6098)

    /** "View Past Flights" card fill. */
    val BlueCardMuted = Color(0xFF3E6690)

    /** Accent orange — recording state, the Ground Speed stat, playback progress fill. */
    val Orange = Color(0xFFE05A2B)

    /** Muted blue-gray used for viewer stat-tile labels. */
    val StatLabel = Color(0xFF8FB3DA)

    /** Body text color for the delete-confirmation dialog and similar light-background text. */
    val DialogText = Color(0xFF4A5A70)

    /** Viewer chrome's darkest background (header bar, playback bar, legend, stat grid gutter). */
    val ViewerBg = Color(0xFF0A2C56)

    /** Viewer stat-grid cell background. */
    val ViewerStatBg = Color(0xFF082349)

    /** Viewer legend row's top border. */
    val ViewerBorder = Color(0xFF14417F)
}

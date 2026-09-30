package com.ma.skydivegps.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ma.skydivegps.R
import com.ma.skydivegps.ui.theme.IBMPlexMono
import com.ma.skydivegps.ui.theme.TailwindColors

/** Live values for the Recording-active card, refreshed by simple polling from MainActivity
 *  (see FlightRecordingService's recordingStartTimeMillisShared / latestSatellitesUsedShared). */
data class RecordingLiveState(
    val elapsedText: String,
    val satellitesText: String
)

/**
 * Splash + Home, combined — matches the Design thread's clickable prototype
 * (`https://claude.ai/artifact/XZHTaWyTAFbrcVC7HrDAgb`, screen "MAIN"). Deliberately simplified
 * from the prototype's single continuously-morphing brand mark (one element sliding/scaling from
 * centered-splash position to corner-home position) to two independently cross-faded layers —
 * per the Design thread's own accepted caveat that this HTML/CSS mock is for direction, not a
 * literal spec, translating exactly is not required, and a shared-element position tween isn't
 * worth the complexity for a first Compose pass. Visually reads the same: big centered mark and
 * tagline give way to a small corner mark plus the three home cards.
 */
@Composable
fun TailwindHomeScreen(
    isRecording: Boolean,
    recordingLive: RecordingLiveState?,
    latestFlightLabel: String?,
    latestFlightDurationText: String?,
    flightCountText: String,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onOpenLatestFlight: () -> Unit,
    onOpenPastFlights: () -> Unit
) {
    // Persists across activity recreation (rotation) but not across a genuine cold start after
    // the app was fully killed — showing the splash again on a fresh open is expected, not a bug.
    var entered by rememberSaveable { mutableStateOf(false) }

    val backgroundBrush = Brush.verticalGradient(
        colorStops = arrayOf(
            0.0f to TailwindColors.NavyDeep,
            0.38f to TailwindColors.Navy,
            0.72f to TailwindColors.BlueMid,
            1.0f to TailwindColors.SkyLight
        )
    )

    val splashAlpha by animateFloatAsState(
        targetValue = if (entered) 0f else 1f,
        animationSpec = tween(durationMillis = 500),
        label = "splashAlpha"
    )
    val markAlpha by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(durationMillis = 700, delayMillis = 200),
        label = "cornerMarkAlpha"
    )
    val homeAlpha by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(durationMillis = 700, delayMillis = 250),
        label = "homeAlpha"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundBrush)
            .then(
                if (!entered) {
                    Modifier.clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { entered = true }
                } else Modifier
            )
    ) {
        if (splashAlpha > 0.001f) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .alpha(splashAlpha),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Image(
                    painter = painterResource(R.drawable.tailwind_mark),
                    contentDescription = null,
                    modifier = Modifier.size(140.dp)
                )
                Spacer(Modifier.height(6.dp))
                Text3("Tailwind", Color.White, 38.sp, FontWeight.ExtraBold)
                Spacer(Modifier.height(20.dp))
                Box(modifier = Modifier.width(280.dp)) {
                    Text3(
                        "Skydive flight tracking & playback",
                        Color.White,
                        15.sp,
                        FontWeight.Normal,
                        align = TextAlign.Center
                    )
                }
            }

            val infiniteTransition = rememberInfiniteTransition(label = "tapHint")
            val pulse by infiniteTransition.animateFloat(
                initialValue = 0.55f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 1000, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "tapHintPulse"
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 64.dp)
                    .alpha(splashAlpha * pulse)
            ) {
                Text3("Tap anywhere to continue", Color.White, 13.sp, FontWeight.Normal)
            }
        }

        if (markAlpha > 0.001f) {
            Row(
                modifier = Modifier
                    .padding(start = 20.dp, top = 40.dp)
                    .alpha(markAlpha),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Image(
                    painter = painterResource(R.drawable.tailwind_mark),
                    contentDescription = null,
                    modifier = Modifier.size(34.dp)
                )
                Text3("Tailwind", Color.White, 18.sp, FontWeight.ExtraBold)
            }
        }

        if (homeAlpha > 0.001f) {
            val cardsAlpha = if (isRecording) 0.55f else 1f

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 190.dp, start = 24.dp, end = 24.dp)
                    .alpha(homeAlpha),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (isRecording) {
                    RecordingActiveCard(recordingLive, onStopRecording)
                } else {
                    RecordIdleCard(onStartRecording)
                }

                ViewLatestFlightCard(
                    label = latestFlightLabel,
                    durationText = latestFlightDurationText,
                    alpha = cardsAlpha,
                    onClick = onOpenLatestFlight
                )

                ViewPastFlightsCard(
                    flightCountText = flightCountText,
                    alpha = cardsAlpha,
                    onClick = onOpenPastFlights
                )
            }

            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 28.dp)
                    .alpha(homeAlpha)
                    .clickable { entered = false }
            ) {
                Text3(
                    "↺ Replay intro",
                    Color.White.copy(alpha = 0.75f),
                    13.sp,
                    FontWeight.Normal,
                    decoration = TextDecoration.Underline
                )
            }
        }
    }
}

@Composable
private fun RecordIdleCard(onStart: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(TailwindColors.NavyDeep)
            .padding(horizontal = 20.dp, vertical = 22.dp)
    ) {
        Text3("READY", Color.White, 13.sp, FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text3("Record Flight", Color.White, 22.sp, FontWeight.ExtraBold)
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White)
                .border(1.5.dp, TailwindColors.Orange, RoundedCornerShape(12.dp))
                .clickable(onClick = onStart)
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(TailwindColors.Orange)
            )
            Text3("Start Recording", TailwindColors.Orange, 15.sp, FontWeight.Bold)
        }
    }
}

@Composable
private fun RecordingActiveCard(live: RecordingLiveState?, onStop: () -> Unit) {
    val infiniteTransition = rememberInfiniteTransition(label = "recDot")
    val dotAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "recDotAlpha"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(TailwindColors.NavyDeep)
            .padding(horizontal = 20.dp, vertical = 22.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(TailwindColors.Orange.copy(alpha = dotAlpha))
            )
            Text3("RECORDING", TailwindColors.Orange, 13.sp, FontWeight.Bold)
        }
        Spacer(Modifier.height(6.dp))
        Text3("Record Flight", Color.White, 22.sp, FontWeight.ExtraBold)
        Spacer(Modifier.height(4.dp))
        Text3(live?.elapsedText ?: "00:00:00", Color.White, 20.sp, FontWeight.SemiBold, mono = true)
        Spacer(Modifier.height(4.dp))
        Text3(live?.satellitesText ?: "Acquiring GPS…", Color.White.copy(alpha = 0.72f), 13.sp, FontWeight.Normal)
        Spacer(Modifier.height(16.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White)
                .clickable(onClick = onStop)
                .padding(horizontal = 18.dp, vertical = 12.dp)
        ) {
            Text3("Stop Recording", TailwindColors.Navy, 15.sp, FontWeight.Bold)
        }
    }
}

@Composable
private fun ViewLatestFlightCard(
    label: String?,
    durationText: String?,
    alpha: Float,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(alpha)
            .clip(RoundedCornerShape(20.dp))
            .background(TailwindColors.BlueCard)
            .padding(horizontal = 20.dp, vertical = 22.dp)
    ) {
        Text3("VIEW LATEST FLIGHT", Color.White, 13.sp, FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text3(
            if (label != null) "$label — Total time ${durationText ?: "--:--"}" else "No flights recorded yet",
            Color.White,
            15.sp,
            FontWeight.Normal
        )
        Spacer(Modifier.height(16.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White)
                .clickable(onClick = onClick)
                .padding(horizontal = 18.dp, vertical = 12.dp)
        ) {
            Text3("Open in 3D Viewer", TailwindColors.Navy, 15.sp, FontWeight.Bold)
        }
    }
}

@Composable
private fun ViewPastFlightsCard(flightCountText: String, alpha: Float, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(alpha)
            .clip(RoundedCornerShape(20.dp))
            .background(TailwindColors.BlueCardMuted)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 22.dp)
    ) {
        Text3("VIEW PAST FLIGHTS", Color.White, 13.sp, FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text3(flightCountText, Color.White.copy(alpha = 0.72f), 14.sp, FontWeight.Normal)
    }
}

/** Small internal helper so every text style in this file goes through one place — this screen
 *  intentionally doesn't use Material3's Typography (no custom Theme.kt yet, see the commit
 *  notes), so this keeps the repetitive color/size/weight/font args consistent instead of
 *  spelling them out at each call site. */
@Composable
private fun Text3(
    text: String,
    color: Color,
    fontSize: TextUnit,
    weight: FontWeight,
    align: TextAlign = TextAlign.Start,
    mono: Boolean = false,
    decoration: TextDecoration? = null
) {
    Text(
        text = text,
        color = color,
        fontSize = fontSize,
        fontWeight = weight,
        textAlign = align,
        fontFamily = if (mono) IBMPlexMono else null,
        textDecoration = decoration,
        letterSpacing = if (weight == FontWeight.Bold || weight == FontWeight.ExtraBold) 0.4.sp else 0.sp
    )
}

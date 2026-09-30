package com.ma.skydivegps.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ma.skydivegps.data.Flight
import com.ma.skydivegps.ui.theme.TailwindColors

/** One row's worth of display data for the Past Flights list — the flight itself plus its
 *  total-duration text (read from its CSV; null if the CSV had fewer than two points). */
data class PastFlightItem(val flight: Flight, val durationText: String?)

/**
 * Past Flights, reskinned — matches the Design thread's clickable prototype
 * (`https://claude.ai/artifact/XZHTaWyTAFbrcVC7HrDAgb`, screen "PAST FLIGHTS"). The most recent
 * flight's card uses the darker navy fill, every other card uses the medium blue — same
 * convention as the prototype.
 */
@Composable
fun TailwindPastFlightsScreen(
    items: List<PastFlightItem>,
    flightCountText: String,
    onBack: () -> Unit,
    onOpenFlight: (Flight) -> Unit,
    onShareFlight: (Flight) -> Unit,
    onRequestDelete: (Flight) -> Unit,
    deleteTarget: Flight?,
    onCancelDelete: () -> Unit,
    onConfirmDelete: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TailwindColors.SkyLight)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.padding(start = 12.dp, end = 20.dp, top = 18.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onBack),
                    contentAlignment = Alignment.Center
                ) {
                    BackChevronIcon()
                }
                Spacer(Modifier.width(4.dp))
                Column {
                    Text2("Past Flights", TailwindColors.Navy, 20.sp, FontWeight.ExtraBold)
                    Text2(flightCountText, TailwindColors.Navy.copy(alpha = 0.72f), 13.sp, FontWeight.Normal)
                }
            }

            if (items.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 24.dp),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Text2("No recorded flights yet.", TailwindColors.Navy, 15.sp, FontWeight.Normal)
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    itemsIndexed(items, key = { _, item -> item.flight.key }) { index, item ->
                        val cardColor = if (index == 0) TailwindColors.Navy else TailwindColors.BlueCardAlt
                        FlightListCard(
                            item = item,
                            backgroundColor = cardColor,
                            onOpen = { onOpenFlight(item.flight) },
                            onShare = { onShareFlight(item.flight) },
                            onDelete = { onRequestDelete(item.flight) }
                        )
                    }
                }
            }
        }

        if (deleteTarget != null) {
            DeleteConfirmDialog(
                label = deleteTarget.label,
                onCancel = onCancelDelete,
                onConfirm = onConfirmDelete
            )
        }
    }
}

@Composable
private fun FlightListCard(
    item: PastFlightItem,
    backgroundColor: Color,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(backgroundColor)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onOpen)
        ) {
            Text2(item.flight.label, Color.White, 15.sp, FontWeight.Bold)
            Spacer(Modifier.height(3.dp))
            Text2(
                "Total time ${item.durationText ?: "--:--"}",
                Color.White.copy(alpha = 0.75f),
                13.sp,
                FontWeight.Normal
            )
        }
        CircleIconButton(onClick = onShare) { ShareIcon() }
        CircleIconButton(onClick = onDelete) { DeleteIcon() }
    }
}

@Composable
private fun CircleIconButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.16f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
private fun DeleteConfirmDialog(label: String, onCancel: () -> Unit, onConfirm: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TailwindColors.NavyDeep.copy(alpha = 0.45f))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onCancel
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .width(280.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.White)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = {} // swallow taps so they don't fall through to the scrim behind
                )
                .padding(horizontal = 20.dp, vertical = 22.dp)
        ) {
            Text2("Delete this flight?", TailwindColors.Navy, 16.sp, FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text2("$label — this can't be undone.", TailwindColors.DialogText, 13.sp, FontWeight.Normal)
            Spacer(Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Box(modifier = Modifier.clickable(onClick = onCancel).padding(4.dp)) {
                    Text2("Cancel", TailwindColors.DialogText, 14.sp, FontWeight.SemiBold)
                }
                Spacer(Modifier.width(20.dp))
                Box(modifier = Modifier.clickable(onClick = onConfirm).padding(4.dp)) {
                    Text2("Delete", TailwindColors.DialogText, 14.sp, FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun BackChevronIcon() {
    Canvas(modifier = Modifier.size(20.dp)) {
        val stroke = Stroke(
            width = size.minDimension * 0.12f,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round
        )
        val path = Path().apply {
            moveTo(size.width * 0.65f, size.height * 0.2f)
            lineTo(size.width * 0.3f, size.height * 0.5f)
            lineTo(size.width * 0.65f, size.height * 0.8f)
        }
        drawPath(path, color = TailwindColors.Navy, style = stroke)
    }
}

@Composable
private fun ShareIcon() {
    Canvas(modifier = Modifier.size(15.dp)) {
        val r = size.minDimension * 0.14f
        val leftC = Offset(size.width * 0.22f, size.height * 0.5f)
        val topC = Offset(size.width * 0.82f, size.height * 0.18f)
        val botC = Offset(size.width * 0.82f, size.height * 0.82f)
        val strokeWidth = size.minDimension * 0.09f
        drawLine(Color.White, leftC, topC, strokeWidth = strokeWidth)
        drawLine(Color.White, leftC, botC, strokeWidth = strokeWidth)
        drawCircle(Color.White, radius = r, center = leftC)
        drawCircle(Color.White, radius = r, center = topC)
        drawCircle(Color.White, radius = r, center = botC)
    }
}

@Composable
private fun DeleteIcon() {
    Canvas(modifier = Modifier.size(14.dp)) {
        val strokeWidth = size.minDimension * 0.12f
        drawLine(
            Color.White,
            Offset(size.width * 0.15f, size.height * 0.22f),
            Offset(size.width * 0.85f, size.height * 0.22f),
            strokeWidth = strokeWidth
        )
        drawLine(
            Color.White,
            Offset(size.width * 0.38f, size.height * 0.22f),
            Offset(size.width * 0.38f, size.height * 0.08f),
            strokeWidth = strokeWidth
        )
        drawLine(
            Color.White,
            Offset(size.width * 0.62f, size.height * 0.22f),
            Offset(size.width * 0.62f, size.height * 0.08f),
            strokeWidth = strokeWidth
        )
        drawLine(
            Color.White,
            Offset(size.width * 0.38f, size.height * 0.08f),
            Offset(size.width * 0.62f, size.height * 0.08f),
            strokeWidth = strokeWidth
        )
        drawLine(
            Color.White,
            Offset(size.width * 0.22f, size.height * 0.22f),
            Offset(size.width * 0.28f, size.height * 0.92f),
            strokeWidth = strokeWidth
        )
        drawLine(
            Color.White,
            Offset(size.width * 0.78f, size.height * 0.22f),
            Offset(size.width * 0.72f, size.height * 0.92f),
            strokeWidth = strokeWidth
        )
        drawLine(
            Color.White,
            Offset(size.width * 0.28f, size.height * 0.92f),
            Offset(size.width * 0.72f, size.height * 0.92f),
            strokeWidth = strokeWidth
        )
    }
}

/** Same purpose as HomeScreen.kt's private Text3 helper, kept as its own small copy here rather
 *  than shared, to keep these two screen files independent while there's no shared Theme.kt yet. */
@Composable
private fun Text2(text: String, color: Color, fontSize: TextUnit, weight: FontWeight) {
    Text(text = text, color = color, fontSize = fontSize, fontWeight = weight)
}

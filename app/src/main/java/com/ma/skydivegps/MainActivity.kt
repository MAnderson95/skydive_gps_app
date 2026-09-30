package com.ma.skydivegps

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.ma.skydivegps.data.Flight
import com.ma.skydivegps.data.FlightCsvReader
import com.ma.skydivegps.data.FlightFileStorage
import com.ma.skydivegps.ui.RecordingLiveState
import com.ma.skydivegps.ui.TailwindHomeScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private enum class Screen { Home, PastFlights }

/** Everything the Home screen needs about recorded flights, computed together off the main
 *  thread (see MainActivity.refreshFlightList) since the duration figure requires actually
 *  reading the latest flight's CSV, not just listing the directory. */
private data class FlightSummaries(
    val flights: List<Flight>,
    val latestFlightLabel: String?,
    val latestFlightDurationText: String?,
    val flightCountText: String
)

class MainActivity : ComponentActivity() {

    private var isRecording by mutableStateOf(false)
    private var flights by mutableStateOf(listOf<Flight>())
    private var latestFlightLabel by mutableStateOf<String?>(null)
    private var latestFlightDurationText by mutableStateOf<String?>(null)
    private var flightCountText by mutableStateOf("0 recorded flights")
    private var currentScreen by mutableStateOf(Screen.Home)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineLocationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        if (fineLocationGranted) {
            startRecording()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshFlightList()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    BackHandler(enabled = currentScreen == Screen.PastFlights) {
                        currentScreen = Screen.Home
                    }

                    // Live chronometer/satellite readout for the Recording-active card, refreshed
                    // by simple polling of FlightRecordingService's shared fields once a second —
                    // same "keep it simple, tune later" status as the eventual Cn0-based
                    // signal-strength label this is standing in for (PROJECT_CONTEXT).
                    var recordingLive by remember { mutableStateOf<RecordingLiveState?>(null) }
                    LaunchedEffect(isRecording) {
                        if (isRecording) {
                            while (true) {
                                val startMillis = FlightRecordingService.recordingStartTimeMillisShared
                                val elapsedSeconds = if (startMillis > 0) {
                                    (System.currentTimeMillis() - startMillis) / 1000
                                } else 0L
                                val satellites = FlightRecordingService.latestSatellitesUsedShared
                                recordingLive = RecordingLiveState(
                                    elapsedText = formatElapsed(elapsedSeconds),
                                    satellitesText = if (satellites != null) {
                                        "GPS locked · $satellites satellites"
                                    } else {
                                        "Acquiring GPS…"
                                    }
                                )
                                delay(1000)
                            }
                        } else {
                            recordingLive = null
                        }
                    }

                    when (currentScreen) {
                        Screen.Home -> TailwindHomeScreen(
                            isRecording = isRecording,
                            recordingLive = recordingLive,
                            latestFlightLabel = latestFlightLabel,
                            latestFlightDurationText = latestFlightDurationText,
                            flightCountText = flightCountText,
                            onStartRecording = { checkPermissionsAndStart() },
                            onStopRecording = { stopRecording() },
                            // Guarded here rather than just relying on the card being dimmed while
                            // recording: the current, still-recording flight hasn't been saved to
                            // disk yet (FlightRecordingService only writes it out in onDestroy),
                            // so opening the viewer mid-recording would silently show a stale old
                            // flight, or nothing at all if this is the very first flight.
                            onOpenLatestFlight = { if (!isRecording) openViewer(null) },
                            onOpenPastFlights = { currentScreen = Screen.PastFlights }
                        )
                        Screen.PastFlights -> PastFlightsScreen(
                            flights = flights,
                            onBackClick = { currentScreen = Screen.Home },
                            onFlightClick = { flight -> openViewer(flight.key) },
                            onShareClick = { flight -> shareFlight(flight.csvFile) },
                            onDeleteConfirmed = { flight -> deleteFlight(flight) }
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Ground-truth sync, not just an onCreate-time check: this also covers the case where
        // the Activity itself wasn't recreated but the service state changed while backgrounded
        // (e.g. the recording hit the auto-stop ceiling, or was stopped by the system). Re-reading
        // the service's actual running state here — instead of trusting `isRecording`, which was
        // only ever a locally-remembered guess of what MainActivity last told the service to do —
        // is what fixes the stale "Start Flight" label bug: isRecording no longer resets to its
        // default `false` just because the Activity was recreated while the foreground service
        // kept running underneath it.
        isRecording = FlightRecordingService.isRunning
    }

    private fun refreshFlightList() {
        lifecycleScope.launch {
            val summaries = withContext(Dispatchers.IO) { computeFlightSummaries() }
            flights = summaries.flights
            latestFlightLabel = summaries.latestFlightLabel
            latestFlightDurationText = summaries.latestFlightDurationText
            flightCountText = summaries.flightCountText
        }
    }

    /** Off-main-thread: lists flights, reads the latest one's CSV for its total duration (only
     *  the latest — not every flight, to keep this cheap), and sums every flight's on-disk size
     *  (CSV + any cached map image) for the "N recorded flights · X.X MB" line. */
    private fun computeFlightSummaries(): FlightSummaries {
        val list = FlightFileStorage.listFlightsAsFlights(this)

        val latest = list.firstOrNull()
        val latestDurationText = latest?.let { flight ->
            val points = FlightCsvReader.readFlight(flight.csvFile)
            if (points.size >= 2) {
                val seconds = (points.last().timestamp - points.first().timestamp) / 1000
                formatDuration(seconds)
            } else {
                null
            }
        }

        val totalBytes = list.sumOf { flight ->
            FlightFileStorage.filesForFlight(this, flight.key).sumOf { it.length() }
        }
        val totalMb = totalBytes / (1024.0 * 1024.0)
        val count = list.size
        val countText = "$count recorded flight${if (count == 1) "" else "s"} · " +
            "${"%.1f".format(totalMb)} MB"

        return FlightSummaries(
            flights = list,
            latestFlightLabel = latest?.label,
            latestFlightDurationText = latestDurationText,
            flightCountText = countText
        )
    }

    private fun formatElapsed(totalSeconds: Long): String {
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return "%02d:%02d:%02d".format(h, m, s)
    }

    private fun formatDuration(totalSeconds: Long): String {
        val m = totalSeconds / 60
        val s = totalSeconds % 60
        return "%d:%02d".format(m, s)
    }

    private fun checkPermissionsAndStart() {
        val permissionsNeeded = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsNeeded.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val allGranted = permissionsNeeded.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        if (allGranted) {
            startRecording()
        } else {
            permissionLauncher.launch(permissionsNeeded.toTypedArray())
        }
    }

    private fun startRecording() {
        val serviceIntent = Intent(this, FlightRecordingService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        isRecording = true
    }

    private fun stopRecording() {
        val serviceIntent = Intent(this, FlightRecordingService::class.java)
        stopService(serviceIntent)
        isRecording = false
        Handler(mainLooper).postDelayed({ refreshFlightList() }, 1000)
    }

    private fun shareFlight(file: File) {
        val uri: Uri = FileProvider.getUriForFile(
            this,
            "com.ma.skydivegps.fileprovider",
            file
        )
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, "Share flight data"))
    }

    private fun deleteFlight(flight: Flight) {
        FlightFileStorage.deleteFlight(this, flight.key)
        refreshFlightList()
    }

    private fun openViewer(flightKey: String?) {
        val intent = Intent(this, FlightViewerActivity::class.java)
        if (flightKey != null) {
            intent.putExtra(FlightViewerActivity.EXTRA_FLIGHT_KEY, flightKey)
        }
        startActivity(intent)
    }
}

// PastFlightsScreen is deliberately left in its original plain-Material3 style for now — its
// Tailwind-palette reskin (matching the Design thread's clickable prototype) is scoped as the
// next UX-reskin commit, kept separate so this one stays focused on Home/Splash.
@Composable
private fun PastFlightsScreen(
    flights: List<Flight>,
    onBackClick: () -> Unit,
    onFlightClick: (Flight) -> Unit,
    onShareClick: (Flight) -> Unit,
    onDeleteConfirmed: (Flight) -> Unit
) {
    var pendingDelete by remember { mutableStateOf<Flight?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBackClick) { Text("< Back") }
            Spacer(modifier = Modifier.width(4.dp))
            Text("Past Flights", style = MaterialTheme.typography.titleMedium)
        }
        Spacer(modifier = Modifier.height(8.dp))

        if (flights.isEmpty()) {
            Text(
                "No recorded flights yet.",
                modifier = Modifier.padding(top = 24.dp)
            )
        }

        LazyColumn {
            items(flights, key = { it.key }) { flight ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable { onFlightClick(flight) }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(flight.label, style = MaterialTheme.typography.bodyMedium)
                        Row {
                            TextButton(onClick = { onShareClick(flight) }) { Text("Share") }
                            TextButton(onClick = { pendingDelete = flight }) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { flight ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete this flight?") },
            text = { Text("This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteConfirmed(flight)
                    pendingDelete = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            }
        )
    }
}

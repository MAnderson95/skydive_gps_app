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
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.ma.skydivegps.data.Flight
import com.ma.skydivegps.data.FlightCsvReader
import com.ma.skydivegps.data.FlightFileStorage
import com.ma.skydivegps.ui.PastFlightItem
import com.ma.skydivegps.ui.RecordingLiveState
import com.ma.skydivegps.ui.TailwindHomeScreen
import com.ma.skydivegps.ui.TailwindPastFlightsScreen
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
    val flightCountText: String,
    val pastFlightItems: List<PastFlightItem>
)

class MainActivity : ComponentActivity() {

    private var isRecording by mutableStateOf(false)
    private var flights by mutableStateOf(listOf<Flight>())
    private var latestFlightLabel by mutableStateOf<String?>(null)
    private var latestFlightDurationText by mutableStateOf<String?>(null)
    private var flightCountText by mutableStateOf("0 recorded flights")
    private var pastFlightItems by mutableStateOf(listOf<PastFlightItem>())
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

                    // Live chronometer/signal-strength readout for the Recording-active card,
                    // refreshed by simple polling of FlightRecordingService's shared fields once
                    // a second. The signal tier itself is already debounced against jitter inside
                    // FlightRecordingService (see TIER_CONFIRM_COUNT), so no further smoothing is
                    // needed here — this just reflects whatever's currently confirmed.
                    var recordingLive by remember { mutableStateOf<RecordingLiveState?>(null) }
                    LaunchedEffect(isRecording) {
                        if (isRecording) {
                            while (true) {
                                val startMillis = FlightRecordingService.recordingStartTimeMillisShared
                                val elapsedSeconds = if (startMillis > 0) {
                                    (System.currentTimeMillis() - startMillis) / 1000
                                } else 0L
                                val signalTier = FlightRecordingService.latestSignalTierShared
                                recordingLive = RecordingLiveState(
                                    elapsedText = formatElapsed(elapsedSeconds),
                                    signalText = if (signalTier != null) {
                                        "GPS signal: ${signalTier.label}"
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

                    var deleteTarget by remember { mutableStateOf<Flight?>(null) }

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
                        Screen.PastFlights -> TailwindPastFlightsScreen(
                            items = pastFlightItems,
                            flightCountText = flightCountText,
                            onBack = { currentScreen = Screen.Home },
                            onOpenFlight = { flight -> openViewer(flight.key) },
                            onShareFlight = { flight -> shareFlight(flight.csvFile) },
                            onRequestDelete = { flight -> deleteTarget = flight },
                            deleteTarget = deleteTarget,
                            onCancelDelete = { deleteTarget = null },
                            onConfirmDelete = {
                                deleteTarget?.let { deleteFlight(it) }
                                deleteTarget = null
                            }
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
            pastFlightItems = summaries.pastFlightItems
        }
    }

    /** Off-main-thread: lists flights, reads every flight's CSV for its total duration (needed
     *  for the Past Flights list, not just the latest one — see PastFlightItem), and sums every
     *  flight's on-disk size (CSV + any cached map image) for the "N recorded flights · X.X MB"
     *  line. Reading every flight's CSV here is fine at the flight counts a personal single-device
     *  app like this actually accumulates; if that ever grows into the hundreds, this is the
     *  place to add caching rather than re-parsing every CSV on every refresh. */
    private fun computeFlightSummaries(): FlightSummaries {
        val list = FlightFileStorage.listFlightsAsFlights(this)

        fun durationTextFor(flight: Flight): String? {
            val points = FlightCsvReader.readFlight(flight.csvFile)
            if (points.size < 2) return null
            val seconds = (points.last().timestamp - points.first().timestamp) / 1000
            return formatDuration(seconds)
        }

        val pastItems = list.map { flight -> PastFlightItem(flight, durationTextFor(flight)) }
        val latestDurationText = pastItems.firstOrNull()?.durationText

        val totalBytes = list.sumOf { flight ->
            FlightFileStorage.filesForFlight(this, flight.key).sumOf { it.length() }
        }
        val totalMb = totalBytes / (1024.0 * 1024.0)
        val count = list.size
        val countText = "$count recorded flight${if (count == 1) "" else "s"} · " +
            "${"%.1f".format(totalMb)} MB"

        return FlightSummaries(
            flights = list,
            latestFlightLabel = list.firstOrNull()?.label,
            latestFlightDurationText = latestDurationText,
            flightCountText = countText,
            pastFlightItems = pastItems
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

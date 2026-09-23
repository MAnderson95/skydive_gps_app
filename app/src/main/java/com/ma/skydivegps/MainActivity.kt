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
import com.ma.skydivegps.data.Flight
import com.ma.skydivegps.data.FlightFileStorage
import java.io.File

private enum class Screen { Home, PastFlights }

class MainActivity : ComponentActivity() {

    private var isRecording by mutableStateOf(false)
    private var flights by mutableStateOf(listOf<Flight>())
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
                    when (currentScreen) {
                        Screen.Home -> HomeScreen(
                            isRecording = isRecording,
                            onStartClick = { checkPermissionsAndStart() },
                            onStopClick = { stopRecording() },
                            onViewLatestClick = { openViewer(null) },
                            onPastFlightsClick = { currentScreen = Screen.PastFlights }
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

    private fun refreshFlightList() {
        flights = FlightFileStorage.listFlightsAsFlights(this)
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

@Composable
private fun HomeScreen(
    isRecording: Boolean,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit,
    onViewLatestClick: () -> Unit,
    onPastFlightsClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Spacer(modifier = Modifier.height(48.dp))
        Text(
            text = if (isRecording) "Recording flight..." else "Ready to record",
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = if (isRecording) onStopClick else onStartClick,
            modifier = Modifier.size(width = 200.dp, height = 60.dp)
        ) {
            Text(if (isRecording) "Stop" else "Start Flight")
        }
        Spacer(modifier = Modifier.height(10.dp))
        OutlinedButton(onClick = onViewLatestClick) {
            Text("View Latest Flight")
        }
        Spacer(modifier = Modifier.height(10.dp))
        OutlinedButton(onClick = onPastFlightsClick) {
            Text("Past Flights")
        }
    }
}

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
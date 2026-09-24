package com.ma.skydivegps

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import com.ma.skydivegps.data.FlightFileStorage
import java.io.File

class MainActivity : ComponentActivity() {

    private var isRecording by mutableStateOf(false)
    private var flightFiles by mutableStateOf(listOf<File>())

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
                    RecordingScreen(
                        isRecording = isRecording,
                        flightFiles = flightFiles,
                        onStartClick = { checkPermissionsAndStart() },
                        onStopClick = { stopRecording() },
                        onShareClick = { file -> shareFlight(file) },
                        onRefreshClick = { refreshFlightList() },
                        onViewTestFlightClick = { openViewer() }
                    )
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
        flightFiles = FlightFileStorage.listFlights(this)
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

    private fun openViewer() {
        startActivity(Intent(this, FlightViewerActivity::class.java))
    }
}

@Composable
fun RecordingScreen(
    isRecording: Boolean,
    flightFiles: List<File>,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit,
    onShareClick: (File) -> Unit,
    onRefreshClick: () -> Unit,
    onViewTestFlightClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
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
            OutlinedButton(onClick = onViewTestFlightClick) {
                Text("Test 3D Viewer")
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Past Flights", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onRefreshClick) {
                Text("Refresh")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn {
            items(flightFiles) { file ->
                Card(modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(file.name, style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = { onShareClick(file) }) {
                            Text("Share")
                        }
                    }
                }
            }
        }
    }
}
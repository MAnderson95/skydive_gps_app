package com.ma.skydivegps

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.ma.skydivegps.data.FlightFileStorage
import com.ma.skydivegps.data.FlightLogger
import com.ma.skydivegps.data.FlightPoint
import com.ma.skydivegps.sensors.BarometerTracker
import com.ma.skydivegps.sensors.GnssStatusTracker
import com.ma.skydivegps.sensors.LocationTracker

class FlightRecordingService : Service() {

    private lateinit var locationTracker: LocationTracker
    private lateinit var barometerTracker: BarometerTracker
    private lateinit var gnssStatusTracker: GnssStatusTracker
    private var wakeLock: PowerManager.WakeLock? = null

    private var latestPressureHpa: Float? = null
    private var latestBaroAltitude: Float? = null
    private var latestSatellitesUsed: Int? = null
    private var latestAvgSignalDb: Float? = null

    private var recordingStartTimeMillis: Long = 0L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val autoStopRunnable = Runnable {
        // Phase 1 hard ceiling: no landing-detection logic, just stop and save so an unattended
        // recording can't run indefinitely (the wake-lock's 1-hour cap only frees the CPU wake
        // lock — it never stopped or saved anything). Invisible/background for now; whether to
        // surface this to the user (e.g. a countdown) is an open design-thread question, not part
        // of this fix.
        stopSelf()
    }
    private val notificationTickRunnable = object : Runnable {
        override fun run() {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, buildNotification())
            mainHandler.postDelayed(this, NOTIFICATION_UPDATE_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        FlightLogger.clear()

        barometerTracker = BarometerTracker(this) { pressureHpa, altitude ->
            latestPressureHpa = pressureHpa
            latestBaroAltitude = altitude
        }

        gnssStatusTracker = GnssStatusTracker(this) { satellitesUsed, avgCn0 ->
            latestSatellitesUsed = satellitesUsed
            latestAvgSignalDb = avgCn0
        }

        locationTracker = LocationTracker(this) { location ->
            val point = FlightPoint(
                timestamp = location.elapsedRealtimeNanos / 1_000_000,
                latitude = location.latitude,
                longitude = location.longitude,
                altitudeGps = location.altitude,
                altitudeBaro = latestBaroAltitude,
                pressureHpa = latestPressureHpa,
                speed = location.speed,
                speedAccuracy = if (location.hasSpeedAccuracy()) location.speedAccuracyMetersPerSecond else null,
                accuracy = location.accuracy,
                verticalAccuracy = if (location.hasVerticalAccuracy()) location.verticalAccuracyMeters else null,
                bearingAccuracy = if (location.hasBearingAccuracy()) location.bearingAccuracyDegrees else null,
                satellitesUsed = latestSatellitesUsed,
                avgSignalDb = latestAvgSignalDb
            )
            FlightLogger.addPoint(point)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Ground truth for MainActivity's isRecording sync (see MainActivity.onResume) — set here,
        // not in onCreate, so it reflects "recording is actually active" rather than just "the
        // service object exists." Only stamp the chronometer's start time on a genuine fresh
        // start, not on a possible repeat onStartCommand call for the same instance (e.g. a
        // START_STICKY restart) — otherwise the elapsed-time notification would jump back to zero
        // mid-recording.
        if (!isRunning) {
            recordingStartTimeMillis = System.currentTimeMillis()
        }
        isRunning = true

        startForeground(NOTIFICATION_ID, buildNotification())
        // Guard against onStartCommand firing more than once for the same service instance
        // (e.g. a START_STICKY restart delivering a fresh intent) stacking up duplicate timers.
        mainHandler.removeCallbacks(notificationTickRunnable)
        mainHandler.removeCallbacks(autoStopRunnable)
        mainHandler.postDelayed(notificationTickRunnable, NOTIFICATION_UPDATE_INTERVAL_MS)
        mainHandler.postDelayed(autoStopRunnable, AUTO_STOP_MS)

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "SkydiveGPS::RecordingWakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire(60 * 60 * 1000L) // safety cap only — auto-releases the wake lock after 1 hour;
                                      // actually stopping/saving the recording is now handled by
                                      // autoStopRunnable above (phase 1: fixed ceiling; a later
                                      // phase can replace this with real landing detection)
        }

        barometerTracker.start()
        gnssStatusTracker.start()
        locationTracker.start()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        mainHandler.removeCallbacks(autoStopRunnable)
        mainHandler.removeCallbacks(notificationTickRunnable)
        locationTracker.stop()
        barometerTracker.stop()
        gnssStatusTracker.stop()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        val points = FlightLogger.getPoints()
        if (points.isNotEmpty()) {
            FlightFileStorage.saveFlight(this, points, recordingStartTimeMillis)
        }
        FlightLogger.clear()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val channelId = "flight_recording_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Flight Recording",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        val satelliteText = latestSatellitesUsed?.let { "$it satellites" } ?: "acquiring satellites"

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Recording flight")
            .setContentText("GPS and altitude logging in progress — $satelliteText")
            // setWhen + setUsesChronometer gives a live, self-updating elapsed-time counter in the
            // notification without needing to repost it every second ourselves — the system ticks
            // it. recordingStartTimeMillis is captured once in onStartCommand and reused on every
            // rebuild so the chronometer's base never resets.
            .setWhen(recordingStartTimeMillis)
            .setUsesChronometer(true)
            // Content should be visible on the lock screen (phone in a pocket/bag mid-jump is the
            // whole point) rather than redacted — the phone's own per-app "show on lock screen"
            // system toggle is a separate, user-side setting this can't flip on your behalf.
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        // 40 min: based on the user's actual jump log, real jumps run ~20-25 min take-off to
        // landing, so this gives real margin. Short-term fallback only — once landing-detection
        // auto-stop ships (blocked on real flight data), this becomes an OR with it rather than
        // being replaced: whichever fires first stops the recording, so an unattended recording
        // still can't run forever if detection has a false negative.
        private const val AUTO_STOP_MS = 40 * 60 * 1000L
        private const val NOTIFICATION_UPDATE_INTERVAL_MS = 60_000L

        // Ground truth for whether the service is actually recording — read by MainActivity
        // (same process; no android:process on the service in the manifest, so a plain static
        // field is safe) instead of trusting a locally-remembered UI flag that resets across
        // Activity recreation.
        @Volatile
        var isRunning: Boolean = false
            private set
    }
}

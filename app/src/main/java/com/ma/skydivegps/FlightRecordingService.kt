package com.ma.skydivegps

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
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
    private var recordingStartTimeMillis: Long = 0L

    private var latestPressureHpa: Float? = null
    private var latestBaroAltitude: Float? = null
    private var latestSatellitesUsed: Int? = null
    private var latestAvgSignalDb: Float? = null

    override fun onCreate() {
        super.onCreate()
        recordingStartTimeMillis = System.currentTimeMillis()
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
        startForeground(NOTIFICATION_ID, buildNotification())

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "SkydiveGPS::RecordingWakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire(60 * 60 * 1000L) // safety cap only — auto-releases the wake lock after 1 hour;
                                      // does NOT stop or save the recording (see roadmap: auto-stop-on-landing)
        }

        barometerTracker.start()
        gnssStatusTracker.start()
        locationTracker.start()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
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

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Recording flight")
            .setContentText("GPS and altitude logging in progress")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 1
    }
}
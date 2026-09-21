package com.ma.skydivegps

import android.os.Bundle
import android.util.Base64
import android.view.Gravity
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.ma.skydivegps.data.FlightCsvReader
import com.ma.skydivegps.data.FlightFileStorage
import com.ma.skydivegps.data.FlightGeoUtils
import com.ma.skydivegps.data.MapImageFetcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class FlightViewerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Which flight to open: for now, the most recently recorded one.
        // Once a real "Past Flights" picker exists, this becomes an intent extra instead.
        val flightFile = FlightFileStorage.listFlights(this).firstOrNull()
        if (flightFile == null) {
            showNoFlightsMessage()
            return
        }

        val webView = WebView(this)
        webView.settings.javaScriptEnabled = true

        val pageReady = CompletableDeferred<Unit>()
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                pageReady.complete(Unit)
            }
        }
        setContentView(webView)
        webView.loadUrl("file:///android_asset/flight_viewer.html")

        lifecycleScope.launch {
            // CSV parsing, coordinate math, and the network fetch all happen off the main thread
            val callJs = withContext(Dispatchers.IO) {
                buildRunViewerCall(flightFile)
            }
            pageReady.await() // don't call into the page before it has actually finished loading
            if (callJs != null) {
                webView.evaluateJavascript(callJs, null)
            }
        }
    }

    private fun buildRunViewerCall(flightFile: File): String? {
        val points = FlightCsvReader.readFlight(flightFile)
        val plan = FlightGeoUtils.plan(points) ?: return null

        val rowsJson = JSONArray()
        for (row in plan.rows) {
            val rowArr = JSONArray()
            for (v in row) rowArr.put(v)
            rowsJson.put(rowArr)
        }

        val metaJson = JSONObject()
            .put("title", flightFile.nameWithoutExtension)
            .put("subtitle", "GPS + barometer log")

        val mapFile = MapImageFetcher.getSatelliteImage(
            context = this,
            cacheKey = flightFile.nameWithoutExtension,
            centerLat = plan.originLat,
            centerLon = plan.originLon,
            zoom = plan.mapZoom,
            sizePx = plan.mapSizePx
        )

        val mapImageArg: String
        val mapMetaArg: String
        if (mapFile != null) {
            val bytes = mapFile.readBytes()
            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            val dataUri = "data:image/png;base64,$base64"
            mapImageArg = JSONObject.quote(dataUri)
            mapMetaArg = JSONObject()
                .put("widthMeters", plan.mapWidthMeters)
                .put("heightMeters", plan.mapHeightMeters)
                .put("centerX", 0.0)
                .put("centerZ", 0.0)
                .toString()
        } else {
            // map fetch failed (offline, quota, key issue, etc.) — still show the flight, just without imagery
            mapImageArg = "null"
            mapMetaArg = "null"
        }

        return "window.runViewer($rowsJson, $metaJson, $mapImageArg, $mapMetaArg);"
    }

    private fun showNoFlightsMessage() {
        val tv = TextView(this)
        tv.text = "No recorded flights yet — record one from the main screen first."
        tv.gravity = Gravity.CENTER
        tv.setPadding(48, 48, 48, 48)
        setContentView(tv)
    }
}
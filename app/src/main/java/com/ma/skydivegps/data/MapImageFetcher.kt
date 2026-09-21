package com.ma.skydivegps.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.ma.skydivegps.BuildConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

object MapImageFetcher {

    private const val TAG = "MapImageFetcher"

    /**
     * Fetches (or returns a cached) satellite image covering the given center point,
     * suitable for texturing under a flight path. Cached by cacheKey so we don't
     * re-fetch (and re-bill) the same flight repeatedly.
     */
    fun getSatelliteImage(
        context: Context,
        cacheKey: String,
        centerLat: Double,
        centerLon: Double,
        zoom: Int = 15,
        sizePx: Int = 640
    ): File? {
        val cacheDir = File(context.cacheDir, "map_tiles")
        if (!cacheDir.exists()) cacheDir.mkdirs()
        val cacheFile = File(cacheDir, "$cacheKey.png")
        if (cacheFile.exists()) return cacheFile

        val urlStr = "https://maps.googleapis.com/maps/api/staticmap" +
            "?center=$centerLat,$centerLon" +
            "&zoom=$zoom" +
            "&size=${sizePx}x${sizePx}" +
            "&maptype=satellite" +
            "&key=${BuildConfig.MAPS_STATIC_API_KEY}"

        return try {
            val connection = URL(urlStr).openConnection() as HttpURLConnection
            attachAndroidRestrictionHeaders(context, connection)
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                connection.inputStream.use { input ->
                    cacheFile.outputStream().use { output -> input.copyTo(output) }
                }
                cacheFile
            } else {
                Log.e(TAG, "Static Maps request failed: HTTP ${connection.responseCode}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch satellite image", e)
            null
        }
    }

    // Attaches the headers Google's "Android apps" key restriction checks against.
    // Client-side identity claim, not cryptographic proof — the daily quota and
    // billing alert are the real backstops, not this header.
    private fun attachAndroidRestrictionHeaders(context: Context, connection: HttpURLConnection) {
        connection.setRequestProperty("X-Android-Package", context.packageName)
        getSigningCertSha1(context)?.let { sha1 ->
            connection.setRequestProperty("X-Android-Cert", sha1)
        }
    }

    private fun getSigningCertSha1(context: Context): String? {
        return try {
            val signatureBytes: ByteArray? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val packageInfo = context.packageManager.getPackageInfo(
                    context.packageName, PackageManager.GET_SIGNING_CERTIFICATES
                )
                packageInfo.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
            } else {
                @Suppress("DEPRECATION")
                val packageInfo = context.packageManager.getPackageInfo(
                    context.packageName, PackageManager.GET_SIGNATURES
                )
                @Suppress("DEPRECATION")
                packageInfo.signatures?.firstOrNull()?.toByteArray()
            }
            signatureBytes ?: return null
            MessageDigest.getInstance("SHA-1").digest(signatureBytes)
                .joinToString("") { "%02X".format(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to compute signing cert SHA-1", e)
            null
        }
    }
}
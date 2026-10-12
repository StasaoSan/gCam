package com.stasao.gcam

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Supplies a small, low-frequency text stamp; video pixels never enter Kotlin. */
class RecorderStampSource(private val context: Context, private val config: RecorderConfig) {
    private val locationManager = context.getSystemService(LocationManager::class.java)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    @Volatile private var latestGps: Location? = null
    @Volatile private var latestNetwork: Location? = null
    private val listener = LocationListener { location ->
        val gps = location.provider == LocationManager.GPS_PROVIDER
        val previous = if (gps) latestGps else latestNetwork
        if (previous == null || location.elapsedRealtimeNanos >= previous.elapsedRealtimeNanos) {
            if (gps) latestGps = location else latestNetwork = location
        }
    }

    fun start() {
        if (!config.stampGps && !config.stampSpeed) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED) return
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            runCatching {
                if (locationManager.isProviderEnabled(provider)) {
                    locationManager.getLastKnownLocation(provider)?.let(listener::onLocationChanged)
                    locationManager.requestLocationUpdates(provider, 1_000L, 0f, listener, Looper.getMainLooper())
                }
            }
        }
    }

    fun stop() {
        runCatching { locationManager.removeUpdates(listener) }
        latestGps = null
        latestNetwork = null
    }

    fun currentText(): String {
        val parts = ArrayList<String>(3)
        if (config.stampTime) parts += dateFormat.format(Date())
        fun fresh(location: Location?, maxAgeNs: Long): Location? = location?.takeIf {
            val age = SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos
            age in 0..maxAgeNs && (!it.hasAccuracy() || it.accuracy <= 200f)
        }
        val coordinates = fresh(latestGps, 10_000_000_000L) ?: fresh(latestNetwork, 10_000_000_000L)
        if (config.stampGps) {
            parts += if (coordinates != null) "GPS %.5f,%.5f".format(Locale.US, coordinates.latitude, coordinates.longitude)
                else "GPS --"
        }
        if (config.stampSpeed) {
            val recentSpeed = fresh(latestGps, 3_000_000_000L)
            parts += if (recentSpeed?.hasSpeed() == true) "${(recentSpeed.speed * 3.6f).toInt().coerceAtLeast(0)} KM/H"
                else "-- KM/H"
        }
        return parts.joinToString("  ")
    }
}

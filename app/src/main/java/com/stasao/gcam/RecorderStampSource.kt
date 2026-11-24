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
import java.util.UUID
import kotlin.math.roundToInt

/** Supplies a small, low-frequency text stamp; video pixels never enter Kotlin. */
class RecorderStampSource(private val context: Context, private val config: RecorderConfig) {
    private val locationManager = context.getSystemService(LocationManager::class.java)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    @Volatile private var latestGps: Location? = null
    @Volatile private var latestNetwork: Location? = null
    private val vehicleSession = UUID.randomUUID().toString()
    @Volatile private var stopped = false
    // This object is cheap; eager construction makes every start/stop generation explicit.
    // Its shared worker is created only after an embedded speed sample is requested.
    private val vehicleSpeedReader=LatestRecorderSpeedReader(::readVehicleSpeed,SystemClock::elapsedRealtime)
    private val listener = LocationListener { location ->
        val gps = location.provider == LocationManager.GPS_PROVIDER
        val previous = if (gps) latestGps else latestNetwork
        if (previous == null || location.elapsedRealtimeNanos >= previous.elapsedRealtimeNanos) {
            if (gps) latestGps = location else latestNetwork = location
        }
    }

    fun start() {
        stopped = false
        vehicleSpeedReader.start()
        if (!requiresLocation(context, config)) return
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
        stopped = true
        vehicleSpeedReader.stop()
        if(requiresLocation(context,config))runCatching { locationManager.removeUpdates(listener) }
        latestGps = null
        latestNetwork = null
        // The panel's telemetry lease expires after 3 seconds without another read.
        // Stop never calls a possibly blocked provider or closes its in-flight client.
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
            if (RecorderHost.embedded(context)) {
                val speed = vehicleSpeed()
                parts += speed?.let { "${it.roundToInt()} KM/H" } ?: "-- KM/H"
            } else {
                val recentSpeed = fresh(latestGps, 3_000_000_000L)
                parts += if (recentSpeed?.hasSpeed() == true) "${(recentSpeed.speed * 3.6f).toInt().coerceAtLeast(0)} KM/H"
                    else "-- KM/H"
            }
        }
        return parts.joinToString("  ")
    }

    private fun vehicleSpeed(): Float? {
        if (stopped) return null
        return vehicleSpeedReader.sample()
    }

    private fun readVehicleSpeed(): LatestRecorderSpeedReader.Reading? {
        if(stopped)return null
        val client=context.contentResolver.acquireUnstableContentProviderClient(RecorderHost.telemetryUri) ?: return null
        return client.use {
            if(stopped)return@use null
            val snapshot = client.call("vehicle_speed", vehicleSession, null) ?: return null
            if (!snapshot.containsKey("speedKmh") || !snapshot.containsKey("sampledAtMs")) return null
            LatestRecorderSpeedReader.Reading(snapshot.getFloat("speedKmh"),snapshot.getLong("sampledAtMs"))
        }
    }

    companion object {
        fun requiresLocation(context: Context, config: RecorderConfig): Boolean =
            config.stampGps || (!RecorderHost.embedded(context) && config.stampSpeed)
    }
}

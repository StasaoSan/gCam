package com.stasao.gcam

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.UUID

/** A recorder-owned lease; the trip-end screen and camera visibility never gate checkpoints. */
class RecorderEngineCheckpointSource(context: Context, private val onStopped: () -> Unit) {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val state = Any()
    private val session = UUID.randomUUID().toString()
    @Volatile private var enabled = false
    private var detector = EngineStopDetector()
    private val reader = EngineCheckpointReader(::readVehicleState, ::receive)
    private val tick = object : Runnable {
        override fun run() = synchronized(state) {
            if (!enabled) return@synchronized
            reader.request()
            main.postDelayed(this, 1000)
            Unit
        }
    }

    fun start() = synchronized(state) {
        if (enabled || !RecorderHost.embedded(context)) return@synchronized
        detector = EngineStopDetector()
        enabled = true
        reader.start()
        main.post(tick)
        Unit
    }

    fun stop() = synchronized(state) {
        enabled = false
        reader.stop()
        main.removeCallbacks(tick)
        detector = EngineStopDetector()
        // No Binder call or worker join: the shared provider lease expires by itself.
    }

    private fun receive(generation: Long, snapshot: EngineStopDetector.Snapshot?) {
        main.post {
            synchronized(state) {
                if (!enabled || !reader.isCurrent(generation)) return@synchronized
                if (detector.update(snapshot ?: EngineStopDetector.Snapshot(), SystemClock.elapsedRealtime())) onStopped()
            }
        }
    }

    private fun readVehicleState(): EngineStopDetector.Snapshot? {
        if (!enabled) return null
        val client = context.contentResolver.acquireUnstableContentProviderClient(RecorderHost.telemetryUri) ?: return null
        return client.use {
            if (!enabled) return@use null
            val snapshot = client.call("vehicle_state", session, null) ?: return@use null
            fun sample(valueKey: String, timeKey: String): EngineStopDetector.Sample? =
                if (snapshot.containsKey(valueKey) && snapshot.containsKey(timeKey))
                    EngineStopDetector.Sample(snapshot.getFloat(valueKey), snapshot.getLong(timeKey)) else null
            EngineStopDetector.Snapshot(
                speedKmh = sample("speedKmh", "speedSampledAtMs"),
                rpm = sample("rpm", "rpmSampledAtMs"),
                telemetryAliveAtMs = snapshot.getLong("telemetryAliveAtMs", -1),
                qnxRpmStopCandidateAtMs = snapshot.getLong("qnxRpmStopCandidateAtMs", -1),
            )
        }
    }
}

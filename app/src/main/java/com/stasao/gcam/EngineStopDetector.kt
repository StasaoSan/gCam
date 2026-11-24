package com.stasao.gcam

/** Shared trip-end confirmation, independent of screens, cameras and recording lifecycle. */
class EngineStopDetector(private val requireObservedRunning: Boolean = true) {
    data class Sample(val value: Float, val sampledAtMs: Long)
    data class Snapshot(
        val speedKmh: Sample? = null,
        val rpm: Sample? = null,
        val telemetryAliveAtMs: Long = -1,
        val qnxRpmStopCandidateAtMs: Long = -1,
    )

    private var zeroSince: Long? = null
    private var confirmingQnxCandidateAt = -1L
    private var observedRunning = false
    private var lastEngineRunningAt = -1L
    private var lastUpdateAt = -1L
    var stopped = false
        private set

    /** Returns true once after a confirmed stop; another running RPM sample rearms it. */
    fun update(snapshot: Snapshot, now: Long, enabled: Boolean = true): Boolean {
        if (lastUpdateAt >= 0 && (now < lastUpdateAt || now - lastUpdateAt > 2500)) resetConfirmation()
        lastUpdateAt = now
        val rpm = snapshot.rpm.fresh(now, 20_000f)
        val speed = snapshot.speedKmh.fresh(now, 500f)
        if (rpm != null && rpm > 350) {
            observedRunning = true
            lastEngineRunningAt = now
        }
        if ((rpm != null && rpm > 350) || (speed != null && speed > 1)) {
            stopped = false
            resetConfirmation()
        }
        if (!enabled) {
            stopped = false
            resetConfirmation()
            return false
        }
        val candidate = snapshot.qnxRpmStopCandidateAtMs
        if (confirmingQnxCandidateAt >= 0 && candidate != confirmingQnxCandidateAt) resetConfirmation()
        val liveQnx = fresh(snapshot.telemetryAliveAtMs, now)
        // A fresh packet can start confirmation. At 1 Hz the same packet can become
        // older than 2.5s before debounce ends; keep only that already-started proof,
        // with fresh independent speed and heartbeat on every subsequent update.
        val confirmingSameCandidate = zeroSince != null && candidate >= 0 && candidate == confirmingQnxCandidateAt
        val shutdownPacket = observedRunning && lastEngineRunningAt >= 0 &&
            candidate >= lastEngineRunningAt && (fresh(candidate, now) || confirmingSameCandidate) && liveQnx
        val stoppedRpm = if (rpm != null) rpm <= 10 else shutdownPacket
        if (speed == null || (rpm == null && !stoppedRpm)) {
            resetConfirmation()
            return false
        }
        if (stoppedRpm && speed <= 1) {
            if (requireObservedRunning && !observedRunning) {
                resetConfirmation()
                return false
            }
            confirmingQnxCandidateAt = if (rpm == null && shutdownPacket) candidate else -1
            val began = zeroSince ?: now.also { zeroSince = it }
            if (!stopped && now - began >= 1800) {
                stopped = true
                observedRunning = false
                lastEngineRunningAt = -1
                confirmingQnxCandidateAt = -1
                return true
            }
        } else {
            resetConfirmation()
            stopped = false
        }
        return false
    }

    private fun Sample?.fresh(now: Long, maxValue: Float): Float? = this?.takeIf {
        it.value.isFinite() && it.value in 0f..maxValue && fresh(it.sampledAtMs, now)
    }?.value

    private fun fresh(sampledAt: Long, now: Long) = sampledAt >= 0 && now >= sampledAt && now - sampledAt <= 2500

    private fun resetConfirmation() { zeroSince = null; confirmingQnxCandidateAt = -1 }
}

/** One shared Binder worker and one replaceable pending read, even when a provider hangs. */
class EngineCheckpointReader(
    private val read: () -> EngineStopDetector.Snapshot?,
    private val consume: (Long, EngineStopDetector.Snapshot?) -> Unit,
    private val actor: Actor = sharedActor,
) {
    class Actor {
        private data class Request(val owner: EngineCheckpointReader, val generation: Long)
        private val lock = java.lang.Object()
        private var pending: Request? = null
        private var started = false

        internal fun submit(owner: EngineCheckpointReader, generation: Long) = synchronized(lock) {
            if (!owner.isCurrent(generation)) return@synchronized
            pending = Request(owner, generation)
            if (!started) {
                started = true
                Thread(::run, "gpanel-engine-checkpoint").apply { isDaemon = true; start() }
            }
            lock.notifyAll()
        }

        internal fun cancel(owner: EngineCheckpointReader) = synchronized(lock) {
            if (pending?.owner === owner) pending = null
        }

        private fun run() { while (true) readNext() }

        private fun readNext() {
            val request = synchronized(lock) {
                while (pending == null) try { lock.wait() } catch (_: InterruptedException) { }
                requireNotNull(pending).also { pending = null }
            }
            request.owner.collect(request.generation)
        }
    }

    private val state = Any()
    private var enabled = false
    private var generation = 0L

    fun start() = synchronized(state) { generation++; enabled = true }

    fun stop() {
        synchronized(state) { generation++; enabled = false }
        actor.cancel(this)
    }

    fun request() {
        val expected = synchronized(state) { if (!enabled) return; generation }
        actor.submit(this, expected)
    }

    fun isCurrent(expected: Long) = synchronized(state) { enabled && generation == expected }

    private fun collect(expected: Long) {
        if (!isCurrent(expected)) return
        val snapshot = runCatching { read() }.getOrNull()
        synchronized(state) { if (enabled && generation == expected) consume(expected, snapshot) }
    }

    companion object { private val sharedActor = Actor() }
}

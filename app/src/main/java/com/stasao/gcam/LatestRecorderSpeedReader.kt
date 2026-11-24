package com.stasao.gcam

/** A cached speed source. All instances share one I/O worker and one latest pending request. */
class LatestRecorderSpeedReader(
    private val read:()->Reading?,
    private val nowMs:()->Long={System.nanoTime()/1_000_000},
    private val actor:Actor=sharedActor,
) {
    data class Reading(val kmh:Float,val sampledAtMs:Long)

    class Actor {
        private data class Request(val owner:LatestRecorderSpeedReader,val generation:Long)
        private val lock=java.lang.Object()
        private var pending:Request?=null
        private var workerStarted=false

        internal fun submit(owner:LatestRecorderSpeedReader,generation:Long)=synchronized(lock) {
            pending=Request(owner,generation)
            if(!workerStarted) {
                workerStarted=true
                Thread(::run,"gpanel-recorder-speed").apply {isDaemon=true;start()}
            }
            lock.notifyAll()
        }

        internal fun cancel(owner:LatestRecorderSpeedReader)=synchronized(lock) {
            if(pending?.owner===owner)pending=null
        }

        private fun run() {
            while(true)readNext()
        }

        private fun readNext() {
            // Pop the request's stack frame before the next idle wait, releasing its source context.
            val request=synchronized(lock) {
                while(pending==null)try {lock.wait()}catch(_:InterruptedException) { /* Keep the single owner. */ }
                requireNotNull(pending).also {pending=null}
            }
            request.owner.collect(request.generation)
        }
    }

    private val state=Any()
    private var enabled=true
    private var generation=0L
    private var cached:Reading?=null

    fun start()=synchronized(state) {generation++;enabled=true;cached=null}

    fun stop() {
        synchronized(state) {generation++;enabled=false;cached=null}
        actor.cancel(this)
    }

    fun sample():Float? {
        val snapshot=synchronized(state) {
            if(!enabled)return null
            generation to valid(cached)?.kmh
        }
        actor.submit(this,snapshot.first)
        return synchronized(state) {snapshot.second.takeIf {enabled && generation==snapshot.first}}
    }

    private fun collect(expectedGeneration:Long) {
        synchronized(state) {if(!enabled || generation!=expectedGeneration)return}
        // I/O, including resource closure, runs without the actor or source state locks.
        val reading=runCatching {read()}.getOrNull()
        synchronized(state) {
            if(enabled && generation==expectedGeneration)cached=valid(reading)
        }
    }

    private fun valid(value:Reading?):Reading?=value?.takeIf {
        val now=nowMs()
        it.kmh.isFinite() && it.kmh in 0f..500f && it.sampledAtMs>=0 &&
            now>=it.sampledAtMs && now-it.sampledAtMs<=2500
    }

    companion object {private val sharedActor=Actor()}
}

package com.stasao.gcam

/** Target durations only: callers still rotate on an encoded keyframe. */
internal class SegmentRotationSchedule(
    private val segmentUs:Long,
    inputId:Int,
    cameraIds:Set<Int>,
    private val stagger:Boolean,
) {
    private val cameraCount=cameraIds.size
    private val cameraOrdinal=cameraIds.sorted().indexOf(inputId)+1

    init {
        require(segmentUs>0)
        require(cameraIds.isNotEmpty() && cameraIds.all {it in 0..3})
        require(inputId in cameraIds)
    }

    fun durationUs(segmentIndex:Int):Long {
        require(segmentIndex>=0)
        if(!stagger || segmentIndex>0)return segmentUs
        // Divide before multiplying: segmentUs may be Long.MAX_VALUE.
        val whole=segmentUs/cameraCount
        val remainder=segmentUs%cameraCount
        return (whole*cameraOrdinal+remainder*cameraOrdinal/cameraCount).coerceAtLeast(1L)
    }
}

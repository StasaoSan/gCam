package com.stasao.gcam

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object RecorderRepository {
    /** Includes startup and MP4 finalization; migration must wait for every encoder to close. */
    @Volatile internal var busy = false
    private val mutableState = MutableStateFlow(RecorderState())
    val state = mutableState.asStateFlow()

    fun update(value: RecorderState) { mutableState.value = value }
}

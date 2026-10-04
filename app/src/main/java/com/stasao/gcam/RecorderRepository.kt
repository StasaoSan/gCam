package com.stasao.gcam

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object RecorderRepository {
    private val mutableState = MutableStateFlow(RecorderState())
    val state = mutableState.asStateFlow()

    fun update(value: RecorderState) { mutableState.value = value }
}

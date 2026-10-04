package com.stasao.gcam

data class RecorderConfig(
    val cameraIds: Set<Int> = setOf(2, 3),
    val bitrateMbps: Int = 4,
    val segmentMinutes: Int = 2,
    val storageLimitGb: Int = 40,
    val reserveGb: Int = 5
)

data class CameraRecordingState(
    val inputId: Int,
    val frames: Long = 0,
    val fps: Double = 0.0,
    val recording: Boolean = false,
    val error: String? = null
)

data class RecorderState(
    val recording: Boolean = false,
    val cameras: Map<Int, CameraRecordingState> = emptyMap(),
    val usedBytes: Long = 0,
    val status: String = "Регистратор остановлен"
)

data class RecordingFile(
    val path: String,
    val name: String,
    val inputId: Int,
    val sizeBytes: Long,
    val modifiedAt: Long,
    val protected: Boolean
)

val recorderCameraNames = mapOf(0 to "Левая", 1 to "Правая", 2 to "Перед", 3 to "Зад")

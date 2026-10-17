package com.stasao.gcam

enum class RecordingStorage { EXTERNAL, INTERNAL }

data class RecorderConfig(
    val cameraIds: Set<Int> = setOf(0, 1, 2, 3),
    val width: Int = 640,
    val height: Int = 400,
    val fps: Int = 15,
    val bitrateMbps: Int = 2,
    val segmentMinutes: Int = 2,
    val storageLimitGb: Int = 40,
    val storage: RecordingStorage = RecordingStorage.EXTERNAL,
    val stampTime: Boolean = false,
    val stampGps: Boolean = false,
    val stampSpeed: Boolean = false
)

data class CameraRecordingState(
    val inputId: Int,
    val frames: Long = 0,
    val fps: Double = 0.0,
    val recording: Boolean = false,
    val error: String? = null,
    val zeroCopy: Boolean? = null,
    val maxGapMs: Double = 0.0,
    val timeouts: Long = 0
)

data class RecorderState(
    val recording: Boolean = false,
    val cameras: Map<Int, CameraRecordingState> = emptyMap(),
    val usedBytes: Long = 0,
    val status: String = "Регистратор остановлен"
)

data class RecordingFile(
    val uri: String,
    val name: String,
    val inputId: Int,
    val sizeBytes: Long,
    val modifiedAt: Long,
    val protected: Boolean,
    val durationMs: Long = 0,
    val storage: RecordingStorage = RecordingStorage.INTERNAL
)

val recorderCameraNames = mapOf(0 to "Левая", 1 to "Правая", 2 to "Перед", 3 to "Зад")

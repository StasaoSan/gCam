package com.stasao.gcam

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable internal fun UsbTestStatusCard(driveId: String?) {
    val context = LocalContext.current
    var message by remember(driveId) { mutableStateOf("") }
    var running by remember(driveId) { mutableStateOf(false) }
    LaunchedEffect(driveId) {
        if (driveId == null) return@LaunchedEffect
        UsbTestService.request(context)
        while (true) {
            val state = withContext(Dispatchers.IO) { UsbConnectionTest.state(context) }
            if (state?.optString("drive").equals(driveId, true)) {
                message = state?.optString(if (GCamLanguage.isEnglish(context)) "en" else "ru").orEmpty()
                running = state?.optBoolean("running") == true
            }
            delay(1_000)
        }
    }
    Column {
        Text(GCamLanguage.text(context,
            "При подключении: тест USB 200 МБ, запись и чтение — до 60 с.",
            "On connection: 200 MB USB test, up to 60 s for writing and verification."))
        if (running) LinearProgressIndicator()
        if (message.isNotBlank()) Text(message)
    }
}

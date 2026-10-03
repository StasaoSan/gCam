package com.stasao.gcam

import android.graphics.SurfaceTexture
import android.os.Bundle
import android.view.Surface
import android.view.TextureView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.stasao.gcam.ui.theme.GCamTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private data class CameraInput(val id: Int, val title: String)
private val cameraInputs = listOf(
    CameraInput(0, "Левая"), CameraInput(1, "Правая"),
    CameraInput(2, "Перед"), CameraInput(3, "Зад")
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { GCamTheme { CameraScreen() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CameraScreen() {
    val scope = rememberCoroutineScope()
    val controller = remember { QCarCamController() }
    var selectedInput by remember { mutableIntStateOf(0) }
    var previewSurface by remember { mutableStateOf<Surface?>(null) }
    var state by remember {
        mutableStateOf(QCarCamState("Подготовка видеопотока…", false, 0, 0, 0, 0.0))
    }

    LaunchedEffect(Unit) { controller.states().collect { state = it } }
    DisposableEffect(Unit) {
        onDispose { controller.stop(); previewSurface?.release() }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Камеры 360°") },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        )
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            CameraStatus(state)
            AndroidView(
                factory = { context ->
                    TextureView(context).apply {
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(
                                texture: SurfaceTexture, width: Int, height: Int
                            ) {
                                previewSurface?.release()
                                val surface = Surface(texture)
                                previewSurface = surface
                                scope.launch(Dispatchers.IO) {
                                    controller.start(surface, selectedInput)
                                }
                            }
                            override fun onSurfaceTextureSizeChanged(
                                texture: SurfaceTexture, width: Int, height: Int
                            ) = Unit
                            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                                scope.launch(Dispatchers.IO) { controller.stop() }
                                previewSurface?.release(); previewSurface = null
                                return true
                            }
                            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().weight(1f).background(Color.Black)
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                cameraInputs.forEach { input ->
                    FilterChip(
                        selected = selectedInput == input.id,
                        onClick = {
                            selectedInput = input.id
                            previewSurface?.takeIf(Surface::isValid)?.let { surface ->
                                scope.launch(Dispatchers.IO) { controller.start(surface, input.id) }
                            }
                        },
                        label = { Text(input.title) }, modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun CameraStatus(state: QCarCamState) {
    val error = state.status.contains("ошибка", true) ||
        state.status.contains("недоступен", true) || state.status.contains("не удалось", true)
    val background = when { state.running -> Color(0xFF1B5E20); error -> Color(0xFF8E2020)
        else -> MaterialTheme.colorScheme.surfaceVariant }
    val foreground = if (state.running || error) Color.White
        else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(Modifier.fillMaxWidth(), color = background) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(state.status, color = foreground, fontSize = 12.sp)
            if (state.frames > 0) Text(
                "%.1f fps · %d".format(java.util.Locale.US, state.fps, state.frames),
                color = foreground, fontSize = 11.sp
            )
        }
    }
}

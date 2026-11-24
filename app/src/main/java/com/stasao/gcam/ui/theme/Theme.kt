package com.stasao.gcam.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.stasao.gcam.RecorderHost

private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80
)

private val LightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40

    /* Other default colors to override
    background = Color(0xFFFFFBFE),
    surface = Color(0xFFFFFBFE),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFF1C1B1F),
    onSurface = Color(0xFF1C1B1F),
    */
)

private val PanelColorScheme = darkColorScheme(
    primary = Color(0xFF91E0FF),
    onPrimary = Color(0xFF071A25),
    primaryContainer = Color(0xFF173749),
    onPrimaryContainer = Color(0xFFEDF5FC),
    secondary = Color(0xFF8FBDCE),
    secondaryContainer = Color(0xFF173749),
    onSecondaryContainer = Color(0xFFEDF5FC),
    tertiary = Color(0xFFFFC780),
    background = Color(0xFF080F19),
    onBackground = Color(0xFFEDF5FC),
    surface = Color(0xFF080F19),
    onSurface = Color(0xFFEDF5FC),
    surfaceVariant = Color(0xFF102133),
    onSurfaceVariant = Color(0xFFB2C6D8),
    outline = Color(0xFF4D6A7F),
    outlineVariant = Color(0xFF264155),
    surfaceContainer = Color(0xFF102133)
)

@Composable
fun GCamTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        RecorderHost.embedded(context) -> PanelColorScheme
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
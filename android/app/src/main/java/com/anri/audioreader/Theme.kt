package com.anri.audioreader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp

// Чернильно-синий для интерфейса и янтарный «маркер» для слова, которое звучит.
private val Light = lightColorScheme(
    primary = Color(0xFF285A86),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD5E3F3),
    onPrimaryContainer = Color(0xFF0D2944),
    secondary = Color(0xFF52606E),
    secondaryContainer = Color(0xFFDDE4EC),
    onSecondaryContainer = Color(0xFF17222D),
    tertiary = Color(0xFFB4610C),
    tertiaryContainer = Color(0xFFFFDDB8),
    onTertiaryContainer = Color(0xFF3B1D00),
    background = Color(0xFFEDF0F3),
    onBackground = Color(0xFF15191D),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF15191D),
    surfaceVariant = Color(0xFFE1E6EB),
    onSurfaceVariant = Color(0xFF5A6570),
    outlineVariant = Color(0xFFCDD4DB),
    error = Color(0xFFB3261E),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF9CC7F2),
    onPrimary = Color(0xFF0C2A46),
    primaryContainer = Color(0xFF203F5E),
    onPrimaryContainer = Color(0xFFD5E3F3),
    secondary = Color(0xFFB7C3CF),
    secondaryContainer = Color(0xFF2A3540),
    onSecondaryContainer = Color(0xFFDDE4EC),
    tertiary = Color(0xFFFFB86B),
    tertiaryContainer = Color(0xFF5A3410),
    onTertiaryContainer = Color(0xFFFFDDB8),
    background = Color(0xFF0E1216),
    onBackground = Color(0xFFE3E7EB),
    surface = Color(0xFF171C21),
    onSurface = Color(0xFFE3E7EB),
    surfaceVariant = Color(0xFF242B32),
    onSurfaceVariant = Color(0xFF9AA6B1),
    outlineVariant = Color(0xFF333C45),
    error = Color(0xFFF2B8B5),
)

val ReadingStyle = TextStyle(fontFamily = FontFamily.Serif, fontSize = 20.sp, lineHeight = 31.sp)

@Composable
fun ReaderTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}

// ---- значки плеера, нарисованные вручную, чтобы не тянуть большую библиотеку иконок ----

@Composable
fun PlayPauseGlyph(playing: Boolean, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        if (playing) {
            val bar = w * 0.26f
            val r = CornerRadius(bar * 0.3f)
            drawRoundRect(color, Offset(w * 0.17f, h * 0.12f), Size(bar, h * 0.76f), r)
            drawRoundRect(color, Offset(w * 0.57f, h * 0.12f), Size(bar, h * 0.76f), r)
        } else {
            val p = Path().apply {
                moveTo(w * 0.24f, h * 0.1f)
                lineTo(w * 0.9f, h * 0.5f)
                lineTo(w * 0.24f, h * 0.9f)
                close()
            }
            drawPath(p, color)
        }
    }
}

@Composable
fun SkipGlyph(forward: Boolean, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val p = Path()
        if (forward) {
            p.moveTo(w * 0.12f, h * 0.15f); p.lineTo(w * 0.68f, h * 0.5f); p.lineTo(w * 0.12f, h * 0.85f); p.close()
            drawPath(p, color)
            drawRoundRect(color, Offset(w * 0.72f, h * 0.15f), Size(w * 0.14f, h * 0.7f), CornerRadius(w * 0.04f))
        } else {
            p.moveTo(w * 0.88f, h * 0.15f); p.lineTo(w * 0.32f, h * 0.5f); p.lineTo(w * 0.88f, h * 0.85f); p.close()
            drawPath(p, color)
            drawRoundRect(color, Offset(w * 0.14f, h * 0.15f), Size(w * 0.14f, h * 0.7f), CornerRadius(w * 0.04f))
        }
    }
}

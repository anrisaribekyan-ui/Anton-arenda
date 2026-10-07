package com.anri.audioreader

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Два цвета настроения серии: основной и дополнительный. */
@Composable
fun moodColors(mood: String): Pair<Color, Color> {
    val dark = isSystemInDarkTheme()
    val (a, b) = when (mood) {
        "warm" -> Color(0xFFE8925A) to Color(0xFFE7C26B)
        "joy" -> Color(0xFFF0B94E) to Color(0xFF7FC8A9)
        "business" -> Color(0xFF5B8DB8) to Color(0xFF8FA6B8)
        "tense" -> Color(0xFFC8574B) to Color(0xFF6B6FB8)
        "dark" -> Color(0xFF55597E) to Color(0xFF2E6B73)
        "sad" -> Color(0xFF6E89B0) to Color(0xFF9A8FB5)
        else -> Color(0xFF6FA8C8) to Color(0xFF8CC0A0)   // calm
    }
    val ca by animateColorAsState(if (dark) a.copy(alpha = 0.30f) else a.copy(alpha = 0.22f), tween(2500), label = "moodA")
    val cb by animateColorAsState(if (dark) b.copy(alpha = 0.24f) else b.copy(alpha = 0.18f), tween(2500), label = "moodB")
    return ca to cb
}

/**
 * Дышащий фон: три размытых пятна медленно плавают, а `pulse` (0..1)
 * чуть раздувает их на каждом слове — фон живёт в ритме голоса.
 */
@Composable
fun BreathingBackground(mood: String, pulse: () -> Float, modifier: Modifier) {
    val (ca, cb) = moodColors(mood)
    val t = rememberInfiniteTransition(label = "breath")
    val phase by t.animateFloat(
        0f, (2 * PI).toFloat(),
        infiniteRepeatable(tween(26000, easing = LinearEasing), RepeatMode.Restart), label = "phase",
    )
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val grow = 1f + pulse() * 0.08f
        fun blob(cx: Float, cy: Float, r: Float, c: Color) = drawCircle(
            brush = Brush.radialGradient(listOf(c, c.copy(alpha = 0f)), center = Offset(cx, cy), radius = r * grow),
            radius = r * grow, center = Offset(cx, cy),
        )
        blob(w * (0.25f + 0.12f * sin(phase)), h * (0.25f + 0.08f * cos(phase * 2)), w * 0.75f, ca)
        blob(w * (0.8f + 0.1f * cos(phase)), h * (0.6f + 0.1f * sin(phase)), w * 0.7f, cb)
        blob(w * (0.45f + 0.15f * sin(phase + 2f)), h * (0.95f + 0.05f * cos(phase)), w * 0.6f, ca.copy(alpha = ca.alpha * 0.7f))
    }
}

/**
 * «Сплит-скрин»: медленные текучие формы в нижней части экрана,
 * как залипательное видео под рассказ, только спокойнее.
 */
@Composable
fun FlowPanel(mood: String, modifier: Modifier) {
    val (ca, cb) = moodColors(mood)
    val strongA = ca.copy(alpha = (ca.alpha * 2.6f).coerceAtMost(0.85f))
    val strongB = cb.copy(alpha = (cb.alpha * 2.6f).coerceAtMost(0.85f))
    val t = rememberInfiniteTransition(label = "flow")
    val p by t.animateFloat(
        0f, (2 * PI).toFloat(),
        infiniteRepeatable(tween(18000, easing = LinearEasing), RepeatMode.Restart), label = "p",
    )
    val seeds = remember { List(6) { Random(it * 7 + 3).let { r -> floatArrayOf(r.nextFloat(), r.nextFloat(), 0.6f + r.nextFloat()) } } }
    Canvas(modifier.blur(28.dp)) {
        val w = size.width
        val h = size.height
        seeds.forEachIndexed { i, s ->
            val speed = s[2]
            val x = w * (0.15f + 0.7f * (0.5f + 0.5f * sin(p * speed + s[0] * 6f)))
            val y = h * (0.2f + 0.6f * (0.5f + 0.5f * cos(p * speed * 1.3f + s[1] * 6f)))
            val r = h * (0.28f + 0.1f * sin(p * 2 + i))
            drawCircle(if (i % 2 == 0) strongA else strongB, r, Offset(x, y))
        }
    }
}

/**
 * Пейзаж прогресса: линия гор во всю ширину. Пройденная часть залита,
 * впереди — едва заметный контур, на гребне точка «где ты сейчас».
 */
@Composable
fun LandscapeProgress(seed: String, progress: Float, line: Color, fill: Color, modifier: Modifier) {
    val heights = remember(seed) {
        val r = Random(seed.hashCode())
        val phases = FloatArray(4) { r.nextFloat() * 6.28f }
        FloatArray(121) { k ->
            val x = k / 120f
            (0.55f + 0.18f * sin(x * 7f + phases[0]) + 0.12f * sin(x * 17f + phases[1]) +
                0.06f * sin(x * 41f + phases[2]) + 0.04f * sin(x * 83f + phases[3])).coerceIn(0.1f, 0.95f)
        }
    }
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        fun pt(k: Int) = Offset(w * k / 120f, h * (1f - heights[k]))
        val ridge = Path().apply {
            moveTo(0f, h * (1f - heights[0]))
            for (k in 1..120) lineTo(pt(k).x, pt(k).y)
        }
        drawPath(ridge, line.copy(alpha = line.alpha * 0.35f), style = Stroke(width = 1.5.dp.toPx()))

        val upto = (progress.coerceIn(0f, 1f) * 120).toInt()
        if (upto > 0) {
            val done = Path().apply {
                moveTo(0f, h)
                for (k in 0..upto) lineTo(pt(k).x, pt(k).y)
                lineTo(pt(upto).x, h)
                close()
            }
            drawPath(done, Brush.verticalGradient(listOf(fill, fill.copy(alpha = 0f))))
            val doneRidge = Path().apply {
                moveTo(0f, h * (1f - heights[0]))
                for (k in 1..upto) lineTo(pt(k).x, pt(k).y)
            }
            drawPath(doneRidge, line, style = Stroke(width = 2.dp.toPx()))
        }
        val here = pt(upto)
        drawCircle(line, 4.dp.toPx(), here)
        drawCircle(line.copy(alpha = 0.25f), 9.dp.toPx(), here)
    }
}

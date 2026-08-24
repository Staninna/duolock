package dev.stan.duolock.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.stan.duolock.R
import dev.stan.duolock.ui.theme.LumenGold
import kotlin.math.sin

/**
 * One firefly's non-repeating wander: each axis is a sum of three sines at
 * mutually irrational frequency ratios, so the path never closes into a
 * recognizable pattern. Parameters are derived per firefly from its index.
 */
private class Firefly(i: Int) {
    private val p1 = 1.7f * i + 0.9f
    private val p2 = 3.1f * i + 2.2f
    private val p3 = 0.6f * i + 4.7f
    val size = if (i == 0) 1f else 0.45f + 0.12f * (i % 3)
    private val speed = 0.55f + 0.11f * (i % 4)

    fun pos(t: Float, w: Float, h: Float): Offset {
        val s = t * speed
        val x = 0.42f * sin(0.31f * s + p1) + 0.33f * sin(0.73f * s + p2) + 0.25f * sin(1.37f * s + p3)
        val y = 0.42f * sin(0.41f * s + p2) + 0.33f * sin(0.89f * s + p3) + 0.25f * sin(1.13f * s + p1)
        return Offset(w / 2 + x * w * 0.42f, h / 2 + y * h * 0.40f)
    }

    fun flicker(t: Float): Float =
        0.72f + 0.28f * sin(1.9f * t + p2) * sin(0.63f * t + p1)
}

/**
 * Nox with a small swarm of lumens drifting around her. The main Lumen is the
 * biggest and brightest; all glow with the energy level (0..25, null = full).
 */
@Composable
fun NoxWithLumen(energy: Int?, modifier: Modifier = Modifier) {
    val glow = ((energy ?: 25).coerceIn(0, 25)) / 25f
    val fireflies = remember { List(5) { Firefly(it) } }

    var t by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) {
            withFrameNanos { now -> t = (now - start) / 1_000_000_000f }
        }
    }

    Box(modifier = modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxWidth().height(200.dp)) {
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(LumenGold.copy(alpha = 0.08f + 0.30f * glow), Color.Transparent),
                    center = center, radius = size.minDimension / 2,
                ),
                radius = size.minDimension / 2, center = center,
            )
        }
        Image(
            painter = painterResource(R.drawable.ic_nox),
            contentDescription = "Nox the owl",
            modifier = Modifier.height(150.dp),
        )
        Canvas(Modifier.fillMaxWidth().height(200.dp)) {
            fireflies.forEach { f ->
                val p = f.pos(t, size.width, size.height)
                val bright = (0.30f + 0.65f * glow) * f.flicker(t)
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(LumenGold.copy(alpha = bright * f.size), Color.Transparent),
                        center = p, radius = 30f * f.size,
                    ),
                    radius = 30f * f.size, center = p,
                )
                drawCircle(
                    color = Color(0xFFFFF3D6).copy(alpha = bright),
                    radius = 4.2f * f.size, center = p,
                )
            }
        }
    }
}

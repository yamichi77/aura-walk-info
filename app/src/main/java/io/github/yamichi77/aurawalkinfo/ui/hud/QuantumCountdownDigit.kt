package io.github.yamichi77.aurawalkinfo.ui.hud

import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.yamichi77.aurawalkinfo.ui.theme.HudDetail
import io.github.yamichi77.aurawalkinfo.ui.theme.HudText
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private val cyan = Color(0xFF7FDBFF)
private const val orbitRadius = 0.65f

internal class CountdownArtwork(seed: Int = 11) {
    class Glyph(val digit: Int, val outline: Path, val particles: List<CountdownParticle>)

    val glyphs = (1..3).map { digit ->
        val paint = Paint().apply {
            textSize = 128f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val text = digit.toString()
        val path = android.graphics.Path()
        paint.getTextPath(text, 0, text.length, 0f, 0f, path)
        val bounds = RectF()
        path.computeBounds(bounds, true)
        path.offset(-bounds.centerX(), -bounds.centerY())
        val factor = 1f / bounds.height()
        path.transform(Matrix().apply { setScale(factor, factor) })

        val measure = PathMeasure(path, false)
        var length = 0f
        do {
            length += measure.length
        } while (measure.nextContour())
        measure.setPath(path, false)
        var contourStart = 0f
        val position = FloatArray(2)
        val random = Random(seed + digit)
        val particles = List(COUNTDOWN_PARTICLE_COUNT) { index ->
            val distance = (index + 0.5f) * length / COUNTDOWN_PARTICLE_COUNT
            while (distance > contourStart + measure.length) {
                contourStart += measure.length
                if (!measure.nextContour()) break
            }
            measure.getPosTan(distance - contourStart, position, null)
            CountdownParticle(CountdownPoint(position[0], position[1]), random)
        }
        Glyph(digit, path.asComposePath(), particles)
    }
}

@Composable
internal fun QuantumCountdownDigit(
    artwork: CountdownArtwork,
    digit: Int,
    deadline: Long,
    active: Boolean,
    digitHeight: Dp,
    modifier: Modifier,
) {
    val secondStart = deadline - digit * COUNTDOWN_SECOND_MILLIS.toLong()
    val elapsed = remember(secondStart) {
        mutableFloatStateOf((SystemClock.elapsedRealtime() - secondStart).toFloat())
    }
    LaunchedEffect(secondStart, active) {
        if (active) {
            do {
                val time = withInfiniteAnimationFrameNanos {
                    (SystemClock.elapsedRealtime() - secondStart).toFloat()
                }
                elapsed.floatValue = time
            } while (time < COUNTDOWN_SECOND_MILLIS)
        }
    }

    Canvas(modifier) {
        val frame = QuantumCountdownFrame(elapsed.floatValue)
        val height = digitHeight.toPx().coerceAtMost(size.minDimension / 1.92f)
        val orbit = height * orbitRadius
        drawCircle(cyan, orbit, alpha = 0.34f, style = Stroke(1.dp.toPx()))
        drawCircle(
            HudDetail,
            radius = 2.dp.toPx(),
            center = center + Offset(cos(frame.electronAngle) * orbit, sin(frame.electronAngle) * orbit),
        )
        translate(center.x, center.y) {
            scale(height, height, pivot = Offset.Zero) {
                for (glyph in artwork.glyphs) {
                    val correct = glyph.digit == digit
                    val alpha = if (correct) frame.correctOutlineAlpha else frame.otherOutlineAlpha
                    if (alpha <= 0f) continue
                    val sway = (1f - frame.convergence) * 0.035f
                    translate(
                        sway * sin(frame.jitterPhase + glyph.digit),
                        sway * cos(frame.jitterPhase + glyph.digit),
                    ) {
                        drawPath(
                            glyph.outline, if (correct) HudText else cyan, alpha = alpha,
                            style = Stroke(1.dp.toPx() / height),
                        )
                    }
                }
            }
        }
        val particles = artwork.glyphs[digit - 1].particles
        for (index in particles.indices) {
            val particle = particles[index]
            drawCircle(
                if (index % 4 == 0) HudText else cyan,
                radius = particle.radiusDp.dp.toPx(),
                center = center + Offset(particle.x(frame) * height, particle.y(frame) * height),
                alpha = frame.particleAlpha,
            )
        }
    }
}

@Composable
internal fun CountdownRipple(progress: Float, modifier: Modifier) {
    Canvas(modifier) {
        drawCircle(
            cyan,
            radius = size.minDimension * (0.12f + 0.36f * progress),
            alpha = 0.8f * (1f - progress),
            style = Stroke(1.dp.toPx()),
        )
    }
}

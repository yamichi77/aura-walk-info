package io.github.yamichi77.aurawalkinfo.ui.hud

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.yamichi77.aurawalkinfo.ui.theme.HudDetail
import io.github.yamichi77.aurawalkinfo.ui.theme.HudText
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

internal val QuantumCyan = Color(0xFF7FDBFF)
private const val orbitRadius = 0.65f

internal class CountdownArtwork(seed: Int = 11) {
    class Glyph(val digit: Int, val outline: Path, val particles: List<CountdownParticle>)

    val glyphs = (1..3).map { digit ->
        val paint = Paint().apply {
            textSize = 128f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val text = digit.toString()
        val path = quantumTextPath(text, paint)
        val bounds = RectF()
        path.computeBounds(bounds, true)
        // Center on the filled area, not the bounds: the flag of a 1 widens its bounds to the left,
        // so bounds centering put its stem visibly right of the 2 and 3.
        path.offset(-glyphMassCenterX(path, bounds), -bounds.centerY())
        val factor = 1f / bounds.height()
        path.transform(Matrix().apply { setScale(factor, factor) })

        Glyph(digit, path.asComposePath(), sampleQuantumPath(path, COUNTDOWN_PARTICLE_COUNT, seed + digit))
    }
}

/** Horizontal center of the filled area of [path], found by drawing it into a small alpha bitmap. */
internal fun glyphMassCenterX(path: android.graphics.Path, bounds: RectF): Float {
    val scale = GlyphRasterHeight / bounds.height()
    val width = ceil(bounds.width() * scale).toInt() + 2
    val height = GlyphRasterHeight.toInt() + 2
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    AndroidCanvas(bitmap).apply {
        translate(1f - bounds.left * scale, 1f - bounds.top * scale)
        scale(scale, scale)
        drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG))
    }
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
    bitmap.recycle()
    var weight = 0.0
    var moment = 0.0
    for (y in 0 until height) {
        for (x in 0 until width) {
            val value = pixels[y * width + x] ushr 24
            weight += value
            moment += value * (x + 0.5)
        }
    }
    if (weight == 0.0) return bounds.centerX()
    return bounds.left + ((moment / weight).toFloat() - 1f) / scale
}

private const val GlyphRasterHeight = 128f

internal fun quantumTextPath(text: String, paint: Paint) = android.graphics.Path().apply {
    paint.getTextPath(text, 0, text.length, 0f, 0f, this)
}

internal fun sampleQuantumPath(path: android.graphics.Path, count: Int, seed: Int): List<QuantumParticle> {
    val measure = PathMeasure(path, false)
    var length = 0f
    do {
        length += measure.length
    } while (measure.nextContour())
    if (length <= 0f) return emptyList()

    measure.setPath(path, false)
    var contourStart = 0f
    val position = FloatArray(2)
    val random = Random(seed)
    return List(count) { index ->
        val distance = (index + 0.5f) * length / count
        while (distance > contourStart + measure.length) {
            contourStart += measure.length
            if (!measure.nextContour()) break
        }
        measure.getPosTan(distance - contourStart, position, null)
        QuantumParticle(QuantumPoint(position[0], position[1]), random)
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
        drawCircle(QuantumCyan, orbit, alpha = 0.34f, style = Stroke(1.dp.toPx()))
        drawQuantumElectron(orbit, frame.electronAngle)
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
                            glyph.outline, if (correct) HudText else QuantumCyan, alpha = alpha,
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
                if (index % 4 == 0) HudText else QuantumCyan,
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
        drawQuantumRipple(progress, size.minDimension * 0.12f, size.minDimension * 0.48f)
    }
}

internal fun DrawScope.drawQuantumElectron(radius: Float, angle: Float, alpha: Float = 1f) {
    drawCircle(
        HudDetail, radius = 2.dp.toPx(),
        center = center + Offset(cos(angle) * radius, sin(angle) * radius),
        alpha = alpha,
    )
}

internal fun DrawScope.drawQuantumRipple(progress: Float, startRadius: Float, endRadius: Float, alpha: Float = 1f) {
    drawCircle(
        QuantumCyan,
        radius = startRadius + (endRadius - startRadius) * progress,
        alpha = 0.8f * (1f - progress) * alpha,
        style = Stroke(1.dp.toPx()),
    )
}

package io.github.yamichi77.aurawalkinfo.spatial

import android.content.Context
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.view.View
import androidx.xr.runtime.Session
import androidx.xr.runtime.math.FloatSize2d
import androidx.xr.runtime.math.IntSize2d
import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Vector3
import androidx.xr.scenecore.AnchorSpace
import androidx.xr.scenecore.PanelEntity
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private const val MARK_PANEL_PIXELS = 1024
private const val CORE_COLOR = 0xFFE0F7FF.toInt()
private const val EDGE_COLOR = 0xFF7FDBFF.toInt()
private const val GLOW_STEPS = 24
private const val GLOW_OPACITY = 0.29f
private const val PIN_DOT_RADIUS = 0.18

internal class StandingMarkPanel(session: Session, context: Context, parent: AnchorSpace, kind: MarkKind) {
    private val artwork = StandingMarkArtwork(context)
    private val panel = PanelEntity.create(
        session, artwork, IntSize2d(MARK_PANEL_PIXELS, MARK_PANEL_PIXELS), "Standing${kind.name}", parent = parent,
    ).apply {
        setEnabled(false)
        cornerRadius = 0f
    }
    private val nativeSize = panel.size
    private var worldSize: FloatSize2d? = null

    fun draw(mark: StandingMark, up: MarkVector, opacity: Float) {
        if (opacity <= 0f) {
            hide()
            return
        }
        val data = mark.artwork
        val size = FloatSize2d(data.width.toFloat(), data.height.toFloat())
        if (worldSize != size) {
            // PanelEntity.size resizes the underlying texture. Scale the fixed-size surface in 3D.
            panel.setScale(Vector3(size.width / nativeSize.width, size.height / nativeSize.height, 1f))
            worldSize = size
        }
        artwork.update(data)
        val center = mark.center(up)
        val rotation = signalPanelRotation(mark.facing, up)
        panel.setPose(Pose(Vector3(center.x.toFloat(), center.y.toFloat(), center.z.toFloat()), rotation))
        panel.setAlpha(opacity)
        if (!panel.isEnabled()) panel.setEnabled(true)
    }

    fun hide() {
        if (panel.isEnabled()) panel.hideSpatialEntity()
    }

    fun removeFromScene() {
        panel.hideSpatialEntity(detach = true)
    }
}

internal class StandingMarkArtwork(context: Context) : View(context) {
    private var drawing: MarkArtwork? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    fun update(data: MarkArtwork) {
        if (drawing == data) return
        drawing = data
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val data = drawing ?: return
        canvas.save()
        canvas.scale(width / data.width.toFloat(), height / data.height.toFloat())
        when (data) {
            is MarkArtwork.Arrow -> {
                val arrow = Path().apply {
                    addLine(data.shaft)
                    addLine(data.head)
                }
                drawStroke(canvas, arrow, data.stroke.toFloat())
            }
            is MarkArtwork.Destination -> drawDestination(canvas, data)
        }
        canvas.restore()
    }

    private fun Path.addLine(points: List<MarkPoint>) {
        val first = points.first()
        moveTo(first.x.toFloat(), first.y.toFloat())
        for (point in points.drop(1)) lineTo(point.x.toFloat(), point.y.toFloat())
    }

    private fun drawDestination(canvas: Canvas, data: MarkArtwork.Destination) {
        val centerX = (data.width / 2).toFloat()
        val bottom = data.height.toFloat()
        val stroke = data.stroke.toFloat()
        val radius = (data.pinScale * PIN_HEAD_RADIUS).toFloat()
        val headY = data.headY.toFloat()
        val tipY = data.tipY.toFloat()
        val tailEndY = data.tailEndY.toFloat()
        val tangent = acos(radius / (tipY - headY))
        val tangentDegrees = Math.toDegrees(tangent.toDouble()).toFloat()
        val outline = Path().apply {
            moveTo(centerX, tipY)
            lineTo(centerX + radius * sin(tangent), headY + radius * cos(tangent))
            arcTo(
                centerX - radius, headY - radius, centerX + radius, headY + radius,
                90f - tangentDegrees, -(360f - 2f * tangentDegrees), false,
            )
            close()
            moveTo(centerX, tipY)
            lineTo(centerX, tailEndY)
        }
        val layer = canvas.saveLayer(0f, 0f, data.width.toFloat(), bottom, null)
        // Clip all three layers, including the glow, at the elevation cutoff.
        val visibleRows = (height * data.visibleHeight / data.height).toInt()
        val clipBottom = (visibleRows * data.height / height).toFloat()
        canvas.clipRect(0f, 0f, data.width.toFloat(), clipBottom)
        drawStroke(canvas, outline, stroke)
        paint.style = Paint.Style.FILL
        paint.color = CORE_COLOR
        canvas.drawCircle(centerX, headY, (data.pinScale * PIN_DOT_RADIUS).toFloat(), paint)
        // Fade the finished line so the core and border do not stack their opacity.
        paint.shader = LinearGradient(0f, tipY, 0f, tailEndY, 0xFFFFFFFF.toInt(), 0x66FFFFFF, Shader.TileMode.CLAMP)
        paint.blendMode = BlendMode.DST_IN
        canvas.drawRect(0f, 0f, data.width.toFloat(), bottom, paint)
        paint.blendMode = null
        paint.shader = null
        canvas.restoreToCount(layer)
    }

    private fun drawStroke(canvas: Canvas, path: Path, stroke: Float) {
        paint.style = Paint.Style.STROKE
        paint.color = EDGE_COLOR
        // Measure the glow in artwork metres so the fixed square texture does not distort it.
        var accumulated = 0f
        for (step in 0 until GLOW_STEPS) {
            val fraction = (step + 1f) / GLOW_STEPS
            val target = GLOW_OPACITY * fraction * fraction * (3f - 2f * fraction)
            val alpha = (255 * (target - accumulated) / (1f - accumulated)).roundToInt()
            if (alpha <= 0) continue
            paint.alpha = alpha
            paint.strokeWidth = stroke * (2f * STROKE_PADDING.toFloat() - 2f * step / GLOW_STEPS)
            canvas.drawPath(path, paint)
            accumulated += (1f - accumulated) * alpha / 255
        }
        paint.alpha = 255
        paint.strokeWidth = stroke
        canvas.drawPath(path, paint)
        paint.color = CORE_COLOR
        paint.strokeWidth = stroke * 0.58f
        canvas.drawPath(path, paint)
    }
}

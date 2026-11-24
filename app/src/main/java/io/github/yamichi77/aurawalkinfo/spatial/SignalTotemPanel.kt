package io.github.yamichi77.aurawalkinfo.spatial

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import androidx.xr.runtime.Session
import androidx.xr.runtime.math.FloatSize2d
import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Quaternion
import androidx.xr.runtime.math.Vector3
import androidx.xr.scenecore.AnchorSpace
import androidx.xr.scenecore.PanelEntity
import kotlin.math.roundToInt

internal class SignalTotemPanel(session: Session, context: Context, parent: AnchorSpace) {
    private val artwork = SignalTotemArtwork(context)
    private val panel = PanelEntity.create(
        session, artwork, FloatSize2d(SIGNAL_WIDTH_METERS.toFloat(), SIGNAL_ROW_HEIGHT_METERS.toFloat()),
        "SignalTotem", parent = parent,
    ).apply {
        setEnabled(false)
        cornerRadius = 0f
    }

    fun draw(totem: SignalTotem, placement: SignalPlacement, up: MarkVector, forward: MarkVector, opacity: Float) {
        val previous = artwork.totem
        if (previous?.heightMeters != totem.heightMeters) {
            panel.size = FloatSize2d(SIGNAL_WIDTH_METERS.toFloat(), totem.heightMeters.toFloat())
        }
        if (previous != totem) {
            artwork.totem = totem
            artwork.invalidate()
        }
        val facing = placement.towardEye.takeIf { it.length() > 1e-6 } ?: forward * -1.0
        val rotation = signalPanelRotation(facing, up)
        panel.setPose(Pose(placement.center.vector3(), rotation))
        panel.setAlpha(opacity)
        if (!panel.isEnabled()) panel.setEnabled(true)
    }

    fun hide() {
        if (panel.isEnabled()) panel.setEnabled(false)
    }

    fun removeFromScene() {
        panel.hideSpatialEntity(detach = true)
    }
}

internal fun signalPanelRotation(facing: MarkVector, up: MarkVector): Quaternion =
    Quaternion.fromLookTowards(facing.vector3(), up.vector3())

private fun MarkVector.vector3() = Vector3(x.toFloat(), y.toFloat(), z.toFloat())

internal class SignalTotemArtwork(context: Context) : View(context) {
    var totem: SignalTotem? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    override fun onDraw(canvas: Canvas) {
        val data = totem ?: return
        canvas.save()
        canvas.scale(width / px(SIGNAL_WIDTH_METERS), height / px(data.heightMeters))
        data.rows.forEachIndexed { index, row ->
            val middle = (index + 0.5f) * px(SIGNAL_ROW_HEIGHT_METERS)
            val radius = px(SIGNAL_DOT_DIAMETER_METERS) / 2f
            paint.color = row.color
            paint.style = Paint.Style.FILL
            if (row.fillOpacity > 0f) {
                paint.alpha = (255 * row.fillOpacity).roundToInt()
                canvas.drawCircle(65f, middle, radius, paint)
            }
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 12f
            paint.alpha = (255 * row.outlineOpacity).roundToInt()
            canvas.drawCircle(65f, middle, radius, paint)

            paint.style = Paint.Style.FILL
            paint.color = data.textColor
            drawText(canvas, row.number.toString(), 130f, middle, px(SIGNAL_TEXT_SIZE_METERS), 100f)
            drawText(canvas, row.label, 250f, middle, px(SIGNAL_TEXT_SIZE_METERS), 250f)
            repeat(row.segments) { segment ->
                val left = 520f + segment * 13f
                canvas.drawRect(left, middle - 55f, left + 8f, middle + 55f, paint)
            }
        }
        paint.color = data.textColor
        drawText(
            canvas, SIGNAL_REFERENCE, 130f, px(data.heightMeters - SIGNAL_FOOTER_HEIGHT_METERS / 2), 120f, 380f,
        )
        canvas.restore()
    }

    // Use millimetre-sized drawing coordinates so font measurement works at a practical pixel size.
    private fun px(meters: Double) = (meters * 1_000).toFloat()

    private fun drawText(canvas: Canvas, text: String, x: Float, middle: Float, size: Float, maxWidth: Float) {
        paint.textSize = size
        paint.textScaleX = 1f
        paint.textScaleX = (maxWidth / paint.measureText(text)).coerceAtMost(1f)
        val baseline = middle - (paint.ascent() + paint.descent()) / 2f
        canvas.drawText(text, x, baseline, paint)
    }
}

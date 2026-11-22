package io.github.yamichi77.aurawalkinfo.ui.hud

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import io.github.yamichi77.aurawalkinfo.settings.HudLayoutConfig as Layout

internal const val GridHorizontalDegrees = 30f
internal const val GridVerticalDegrees = 20f
internal val gridWidthMeters = 2 * HudPlacement.planeHalfSpan(GridHorizontalDegrees)
internal val gridHeightMeters = 2 * HudPlacement.planeHalfSpan(GridVerticalDegrees)

@Composable
internal fun DebugAngleGrid(modifier: Modifier = Modifier) {
    val paint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textAlign = Paint.Align.CENTER
        }
    }
    Canvas(modifier) {
        val amber = Color(0xFFFAC775)
        val white = Color.White.copy(alpha = 0.75f)
        val stroke = Stroke(1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 5f)))
        val xPerMeter = size.width / gridWidthMeters
        val yPerMeter = size.height / gridHeightMeters
        fun x(degrees: Float) = center.x + HudPlacement.planeHalfSpan(degrees) * xPerMeter
        fun y(degrees: Float) = center.y - HudPlacement.planeHalfSpan(degrees) * yPerMeter

        val clearLeft = x(-Layout.clearZoneDegrees)
        val clearSize = Size(x(Layout.clearZoneDegrees) - clearLeft, size.height)
        drawRect(amber.copy(alpha = 0.08f), Offset(clearLeft, 0f), clearSize)
        drawRect(amber, Offset(clearLeft, 0f), clearSize, style = stroke)
        drawRect(white, Offset(0.5f, 0.5f), Size(size.width - 1, size.height - 1), style = stroke)
        drawLine(white, Offset(0f, center.y), Offset(size.width, center.y), pathEffect = stroke.pathEffect)

        val labelHeight = size.height * 0.045f
        paint.textSize = labelHeight
        for (degrees in listOf(-30f, -20f, -15f, -10f, 0f, 10f, 15f, 20f, 30f)) {
            val mark = x(degrees).coerceIn(1f, size.width - 1f)
            drawLine(white, Offset(mark, center.y - labelHeight / 3), Offset(mark, center.y + labelHeight / 3))
            val label = "${kotlin.math.abs(degrees.toInt())}°"
            val inset = paint.measureText(label) / 2 + 2
            drawIntoCanvas {
                it.nativeCanvas.drawText(label, mark.coerceIn(inset, size.width - inset), center.y - labelHeight / 2, paint)
            }
        }
        for (degrees in listOf(-20f, -15f, -10f, 10f, 15f, 20f)) {
            val mark = y(degrees).coerceIn(1f, size.height - 1f)
            drawLine(white, Offset(center.x - labelHeight / 3, mark), Offset(center.x + labelHeight / 3, mark))
            val baseline = (mark + labelHeight / 3).coerceIn(labelHeight, size.height - 2f)
            drawIntoCanvas {
                it.nativeCanvas.drawText("${degrees.toInt()}°", center.x + labelHeight * 1.6f, baseline, paint)
            }
        }
        drawIntoCanvas {
            it.nativeCanvas.drawText("L", x(-26f), y(15f), paint)
            it.nativeCanvas.drawText("R", x(26f), y(15f), paint)
        }
    }
}

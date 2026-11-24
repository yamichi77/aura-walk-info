package io.github.yamichi77.aurawalkinfo.ui.hud

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import io.github.yamichi77.aurawalkinfo.nav.NavPoint
import io.github.yamichi77.aurawalkinfo.session.MapCenter

internal interface BigMapGestureMap {
    fun scrollBy(pixels: Offset): MapCenter
    fun pointAt(pixel: Offset): NavPoint?
}

internal class BigMapViewport {
    private var pannedCenter by mutableStateOf<MapCenter?>(null)
    private var zoomOverride by mutableStateOf<Float?>(null)
    var gestureMap: BigMapGestureMap? = null

    fun center(fix: MapCenter?) = pannedCenter ?: fix
    fun zoom(mode: BigMapMode) = zoomOverride ?: mode.zoom

    fun zoomBy(mode: BigMapMode, amount: Int) {
        zoomOverride = (zoom(mode) + amount).coerceIn(mode.minZoom, mode.maxZoom)
    }

    fun recenter() {
        pannedCenter = null
    }

    fun resetZoom() {
        zoomOverride = null
    }

    fun pan(drag: Offset) {
        gestureMap?.let { pannedCenter = it.scrollBy(cameraScrollForDrag(drag)) }
    }
}

// Scroll the camera in the opposite direction so the map follows the pointer.
internal fun cameraScrollForDrag(drag: Offset) = -drag

internal const val BigMapHoldMillis = 800L

internal fun Modifier.bigMapGestures(
    onPan: (Offset) -> Unit,
    onHold: (Offset) -> Unit,
    onOutside: () -> Unit,
    onTap: () -> Unit = {},
) = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown()
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = minOf(size.width, size.height) / 2f
        if ((down.position - center).getDistance() > radius) {
            if (waitForUpOrCancellation() != null) onOutside()
            return@awaitEachGesture
        }
        val result = withTimeoutOrNull(BigMapHoldMillis) {
            // Wrap the nullable result to distinguish an early release from a completed hold.
            Result.success(awaitTouchSlopOrCancellation(down.id) { change, overSlop ->
                change.consume()
                onPan(overSlop)
            })
        }
        if (result == null) {
            onHold(down.position)
        } else {
            val firstDrag = result.getOrNull()
            if (firstDrag == null) {
                // Released before the touch slop and before the hold: a plain tap closes the big map.
                val released = currentEvent.changes.firstOrNull { it.id == down.id }?.pressed == false
                if (released) onTap()
                return@awaitEachGesture
            }
            drag(firstDrag.id) { change ->
                val delta = change.positionChange()
                change.consume()
                if (delta != Offset.Zero) onPan(delta)
            }
        }
    }
}

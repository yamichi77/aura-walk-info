package io.github.yamichi77.aurawalkinfo.ui.hud

import android.os.SystemClock
import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.yamichi77.aurawalkinfo.isDebugBuild
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull

@Composable
internal fun NavArrowCanvas(
    input: NavArrowInput,
    headBearing: (Long) -> Double?,
    opacity: Float,
    modifier: Modifier = Modifier,
) {
    val debug = LocalContext.current.isDebugBuild()
    val latestInput = rememberUpdatedState(input)
    val latestHeadBearing = rememberUpdatedState(headBearing)
    val aim = remember { mutableStateOf<NavArrowAim?>(null) }
    val visible = remember { derivedStateOf { aim.value != null } }
    LaunchedEffect(debug) {
        val updates = NavArrowUpdates()
        var first = true
        while (true) {
            val now = SystemClock.elapsedRealtime()
            val next = updates.update(now, latestInput.value.aim(latestHeadBearing.value(now)))
            if (debug && (first || next != aim.value)) {
                val angle = next?.angle?.let { "%.1f".format(Locale.ROOT, it) } ?: "NA"
                Log.i("AuraNav", "arrow reference=${next?.reference ?: "NONE"} angleDeg=$angle")
            }
            first = false
            aim.value = next
            delay(NavArrowUpdates.intervalMillis)
        }
    }
    if (visible.value) {
        val color = Color(if (input.noFix) NavArrowStyle.noFixColor else NavArrowStyle.color)
        AnimatedNavArrow(aim, color, opacity.coerceAtMost(NavArrowStyle.maxOpacity), modifier)
    }
}

@Composable
private fun AnimatedNavArrow(aim: State<NavArrowAim?>, color: Color, opacity: Float, modifier: Modifier) {
    val rotation = remember { Animatable(checkNotNull(aim.value).angle.toFloat()) }
    LaunchedEffect(aim) {
        snapshotFlow { aim.value?.angle }.filterNotNull().collectLatest { target ->
            val from = rotation.value.toDouble()
            rotation.animateTo(
                (from + shortestArrowTurn(from, target)).toFloat(),
                tween(NavArrowStyle.rotationMillis),
            )
        }
    }
    Canvas(modifier.testTag("nav_arrow").alpha(opacity)) {
        val stroke = NavArrowStyle.strokeDp.dp.toPx()
        val scale = (size.minDimension - stroke).coerceAtLeast(0f) / (2 * NavArrowStyle.projectionHalfSpan)
        val wireframe = Path()
        for ((from, to) in NavArrowStyle.project(rotation.value.toDouble())) {
            wireframe.moveTo(center.x + from.x * scale, center.y + from.y * scale)
            wireframe.lineTo(center.x + to.x * scale, center.y + to.y * scale)
        }
        drawPath(wireframe, color, style = Stroke(stroke))
    }
}

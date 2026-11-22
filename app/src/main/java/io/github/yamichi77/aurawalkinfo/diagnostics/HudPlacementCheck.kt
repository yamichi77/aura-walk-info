package io.github.yamichi77.aurawalkinfo.diagnostics

import android.util.Log
import androidx.xr.arcore.ArDevice
import androidx.xr.arcore.TrackingState
import androidx.xr.runtime.Session
import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Quaternion
import androidx.xr.runtime.math.Vector3
import androidx.xr.scenecore.Entity
import androidx.xr.scenecore.Space
import androidx.xr.scenecore.scene
import io.github.yamichi77.aurawalkinfo.settings.HudLayoutConfig as Layout
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

private const val Tag = "AuraPlacement"

internal enum class HudPanel(val yaw: Float, val pitch: Float) {
    LEFT(Layout.leftYaw, Layout.pitch),
    RIGHT(Layout.rightYaw, Layout.pitch),
    GRID(0f, 0f),
}

internal class HudPlacementCheck {
    data class Panel(val entity: Entity, val width: Float, val height: Float)

    val panels = mutableMapOf<HudPanel, Panel>()

    /** True means the pose was unavailable and the visual A/B fallback is needed. */
    suspend fun run(session: Session, expected: List<HudPanel>): Boolean {
        val scene = session.scene
        val device = ArDevice.getInstance(session)
        var firstHead: Pose? = null
        var headMoved = false
        var allPass = true
        var stable = true
        var measuredSamples = 0
        val firstRelativePoses = mutableMapOf<HudPanel, Pose>()
        repeat(5) { sample ->
            delay(2_000)
            val tracking = device.state.value
            val placed = expected.associateWith { panels[it] }
            if (tracking.trackingState == TrackingState.TRACKING &&
                placed.values.all { it != null && !it.entity.isDisposed && it.width > 0 && it.height > 0 }
            ) {
                val devicePose = tracking.devicePose
                val initial = firstHead
                if (initial == null) firstHead = devicePose
                else headMoved = headMoved || Pose.distance(initial, devicePose) >= 0.02f ||
                    Quaternion.angle(initial.rotation, devicePose.rotation) >= 3f
                val head = scene.perceptionSpace.transformPoseTo(devicePose, scene.activitySpace)
                measuredSamples++
                for ((name, panel) in placed) {
                    val measured = checkNotNull(panel)
                    val pose = head.inverse.compose(measured.entity.getPose(Space.ACTIVITY))
                    val first = firstRelativePoses.getOrPut(name) { pose }
                    stable = stable && Vector3.angleBetween(first.translation, pose.translation) <= 1f &&
                        abs(first.translation.length - pose.translation.length) <= 0.05f &&
                        Quaternion.angle(first.rotation, pose.rotation) <= 5f
                    val result = measure(name, pose, measured.width, measured.height)
                    allPass = allPass && result.pass
                    Log.i(Tag, "sample=${sample + 1} ${name.name} ${verdict(result.pass)} ${result.values}")
                }
            } else {
                Log.i(Tag, "sample=${sample + 1} unavailable tracking=${tracking.trackingState}")
            }
        }
        val complete = measuredSamples == 5
        Log.i(Tag, "placement ${verdict(complete && allPass)} samples=$measuredSamples/5")
        Log.i(Tag, "follow ${verdict(complete && allPass && stable && headMoved)} headMoved=$headMoved stable=$stable")
        return measuredSamples == 0
    }
}

private data class Measurement(val pass: Boolean, val values: String)

private fun measure(name: HudPanel, pose: Pose, width: Float, height: Float): Measurement {
    val center = pose.translation
    val yaw = yawOf(center)
    val pitch = Math.toDegrees(atan2(center.y, hypot(center.x, center.z)).toDouble())
    val normal = Vector3.angleBetween(pose.backward, -center)
    val distance = center.length
    val innerEdge = when (name) {
        HudPanel.LEFT -> listOf(-height / 2, height / 2).maxOf { y ->
            yawOf(pose.transformPoint(Vector3(width / 2, y, 0f)))
        }
        HudPanel.RIGHT -> {
            // Project the circular edge into the horizontal plane. The derivative
            // of its bearing vanishes at the two tangents from the head.
            val radius = minOf(width, height) / 2
            val horizontal = Vector3(center.x, 0f, center.z)
            val u = pose.right * radius
            val v = pose.up * radius
            val a = horizontal.cross(v).y.toDouble()
            val b = -horizontal.cross(u).y.toDouble()
            val c = -u.cross(v).y.toDouble()
            val phase = atan2(b, a)
            val delta = kotlin.math.acos((c / hypot(a, b)).coerceIn(-1.0, 1.0))
            listOf(phase - delta, phase + delta).minOf { angle ->
                yawOf(center + u * kotlin.math.cos(angle).toFloat() + v * kotlin.math.sin(angle).toFloat())
            }
        }
        HudPanel.GRID -> 0.0 // Its transparent panel intentionally spans the center.
    }
    val requiredEdge = Layout.clearZoneDegrees + Layout.clearGapDegrees
    val edgePass = when (name) {
        HudPanel.LEFT -> innerEdge <= -requiredEdge
        HudPanel.RIGHT -> innerEdge >= requiredEdge
        HudPanel.GRID -> true
    }
    val pass = abs(yaw - name.yaw) <= 1 && abs(pitch - name.pitch) <= 1 &&
        edgePass && normal <= 5 && abs(distance - Layout.distanceMeters) <= 0.05f
    val edge = if (name == HudPanel.GRID) "対象外" else direction(innerEdge, "左", "右")
    return Measurement(
        pass,
        "yaw=${direction(yaw, "左", "右")} pitch=${direction(pitch, "下", "上")} " +
            "distance=${"%.3f".format(Locale.ROOT, distance)}m innerEdge=$edge normal=${"%.2f".format(Locale.ROOT, normal)}°",
    )
}

private fun yawOf(point: Vector3) = Math.toDegrees(atan2(point.x, -point.z).toDouble())

private fun direction(angle: Double, negative: String, positive: String) =
    "${if (angle == 0.0) "" else if (angle < 0) negative else positive}${"%.2f".format(Locale.ROOT, abs(angle))}°"

private fun verdict(pass: Boolean) = if (pass) "PASS" else "FAIL"

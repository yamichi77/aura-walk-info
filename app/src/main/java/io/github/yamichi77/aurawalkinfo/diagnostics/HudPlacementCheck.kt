package io.github.yamichi77.aurawalkinfo.diagnostics

import android.util.Log
import androidx.xr.runtime.Session
import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Vector3
import androidx.xr.scenecore.Entity
import androidx.xr.scenecore.PanelEntity
import androidx.xr.scenecore.Space
import androidx.xr.scenecore.scene
import io.github.yamichi77.aurawalkinfo.ui.hud.HudPlacement
import io.github.yamichi77.aurawalkinfo.safety.HeadMotionGate
import io.github.yamichi77.aurawalkinfo.safety.HideHudOnHeadMotion
import io.github.yamichi77.aurawalkinfo.settings.HudLayoutConfig as Layout
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

private const val Tag = "AuraPlacement"

internal enum class HudPanel(val yaw: Float, val pitch: Float) {
    LEFT(Layout.leftYaw, Layout.pitch),
    RIGHT(Layout.rightYaw, Layout.pitch),
    GRID(0f, 0f),
    BIGMAP(0f, HudPlacement.bigMapPitch),
    ;

    /** Pitch of the panel center. The left panel grows upward from its anchor for the clock row (T14). */
    val centerPitch: Float get() = if (this == LEFT) HudPlacement.statsCenterPitch else pitch
}

internal class HudPlacementCheck(includeGrid: Boolean) {
    data class Panel(val entity: Entity, val localWidth: Float, val localHeight: Float)

    val panels = mutableMapOf<HudPanel, Panel>()
    private val clusters = listOf(HudPanel.LEFT, HudPanel.RIGHT)
    private val expected = clusters + if (includeGrid) listOf(HudPanel.GRID) else emptyList()
    private val stationary = expected.associateWith { mutableListOf<Measurement>() }
    private val clearZone = clusters.associateWith { VisibleClearZone() }
    private var startedAt: Long? = null
    private var poseAvailable = false
    private var rotatingSamples = 0
    private var visibleSamples = 0
    private var missingPanelSamples = 0
    private var hiddenGridSamples = 0

    /** Report the measured panel after layout, independently of the regular placement window. */
    fun sampleBigMap(session: Session, headPose: Pose): Boolean {
        val name = HudPanel.BIGMAP
        val panel = panels[name] ?: return false
        if (panel.entity.isDisposed || panel.localWidth <= 0 || panel.localHeight <= 0) return false
        if (panelOpacity(name)?.let { it > 0f } != true) return false
        val scene = session.scene
        val inverseHead = scene.perceptionSpace.transformPoseTo(headPose, scene.activitySpace).inverse
        val relative = inverseHead.compose(panel.entity.getPose(Space.ACTIVITY))
        val scale = panel.entity.getScale(Space.ACTIVITY)
        val width = panel.localWidth * scale
        val height = panel.localHeight * scale
        val measured = measure(name, relative, width, height)
        val diameter = Math.toDegrees(atan2(minOf(width, height) / 2, measured.distance).toDouble()) * 2
        val pass = abs(measured.yaw - name.yaw) <= 1 && abs(measured.pitch - name.centerPitch) <= 1 &&
            abs(diameter - HudPlacement.bigMapDiameter) <= 1 && abs(width - height) <= 0.01f &&
            abs(measured.distance - Layout.distanceMeters) <= 0.05f
        Log.i(Tag, "BIGMAP ${verdict(pass)} yaw=${decimal(measured.yaw)} pitch=${decimal(measured.pitch)} " +
            "diameter=${decimal(diameter)} distance=${decimal(measured.distance.toDouble(), 3)}")
        return true
    }

    /** Null while sampling. Non-null once the check has finished. */
    fun sample(session: Session, now: Long, pose: Pose?, speed: Double?, hiddenSince: Long?): Boolean? {
        val start = startedAt ?: now.also { startedAt = it }
        if (pose != null) poseAvailable = true

        if (pose != null && (!HideHudOnHeadMotion || hiddenSince == null)) {
            val isStationary = speed != null && speed < HeadMotionGate.StationaryDegreesPerSecond
            val scene = session.scene
            val inverseHead = scene.perceptionSpace.transformPoseTo(pose, scene.activitySpace).inverse
            for (name in expected) {
                val visible = clearZone[name]
                if (!isStationary && visible == null) continue
                val panel = panels[name] ?: continue
                if (panel.entity.isDisposed || panel.localWidth <= 0 || panel.localHeight <= 0) continue
                if (panelOpacity(name)?.let { it > 0f } != true) continue
                val relative = inverseHead.compose(panel.entity.getPose(Space.ACTIVITY))
                val scale = panel.entity.getScale(Space.ACTIVITY)
                val measurement = measure(name, relative, panel.localWidth * scale, panel.localHeight * scale)
                if (isStationary) stationary.getValue(name) += measurement
                visible?.record(measurement.edge, speed)
            }
        }

        // Allow the fade and up to two 30 Hz frames for Compose/SceneCore to apply it.
        if (HideHudOnHeadMotion && speed != null && speed > HeadMotionGate.HideDegreesPerSecond && hiddenSince != null &&
            now - hiddenSince >= HeadMotionGate.HideMillis * 1_000_000L + 67_000_000L
        ) {
            rotatingSamples++
            val opacities = clusters.map { panelOpacity(it) }
            if (opacities.any { it == null }) missingPanelSamples++
            if (opacities.any { it != null && it != 0f }) visibleSamples++
            if (HudPanel.GRID in expected && panelOpacity(HudPanel.GRID) != 1f) hiddenGridSamples++
        }
        if (now - start < 10_000_000_000L) return null

        val placementPass = stationary.map { (name, samples) -> reportPlacement(name, samples) }.all { it }
        Log.i(Tag, "placement ${verdict(placementPass)} staticSamples=${stationary.values.minOf { it.size }}")
        val clearZonePass = clearZone.map { (name, samples) -> samples.report(name) }.all { it }
        Log.i(Tag, "clearzone ${verdict(clearZonePass)}")
        if (HideHudOnHeadMotion) {
            val followPass = rotatingSamples > 0 && visibleSamples == 0 && missingPanelSamples == 0 && hiddenGridSamples == 0
            Log.i(Tag, "follow ${verdict(followPass)} rotatingSamples=$rotatingSamples " +
                "nonzeroAlpha=$visibleSamples missingPanels=$missingPanelSamples gridNotVisible=$hiddenGridSamples")
        } else {
            Log.i(Tag, "follow SKIP head-motion hide disabled")
        }
        return !poseAvailable
    }

    private fun panelOpacity(name: HudPanel): Float? {
        val wrapper = panels[name]?.entity?.takeUnless { it.isDisposed } ?: return null
        return wrapper.children.filterIsInstance<PanelEntity>().singleOrNull()?.getAlpha(Space.ACTIVITY)
    }
}

private class VisibleClearZone {
    private var count = 0
    private var minimumEdge = Double.POSITIVE_INFINITY
    private var speedAtMinimum: Double? = null

    fun record(edge: Double, speed: Double?) {
        count++
        if (edge.isNaN() || edge < minimumEdge) {
            minimumEdge = edge
            speedAtMinimum = speed
        }
    }

    fun report(name: HudPanel): Boolean {
        val pass = minimumEdge.isFinite() && minimumEdge > Layout.clearZoneDegrees
        val edge = if (count > 0) "${decimal(minimumEdge)}°" else "unavailable"
        val speed = speedAtMinimum?.let { "${decimal(it)}°/s" } ?: "unavailable"
        Log.i(Tag, "${name.name} clearzone ${verdict(pass)} visibleSamples=$count " +
            "innerEdgeMinVisible=$edge speedAtMin=$speed")
        return pass
    }
}

private data class Measurement(val yaw: Double, val pitch: Double, val edge: Double, val normal: Float, val distance: Float)

private fun reportPlacement(name: HudPanel, samples: List<Measurement>): Boolean {
    if (samples.isEmpty()) {
        Log.i(Tag, "${name.name} FAIL no stationary samples")
        return false
    }
    val yaw = samples.map { it.yaw }.median()
    val pitch = samples.map { it.pitch }.median()
    val edge = samples.map { it.edge }.median()
    val nearestEdge = samples.minOf { it.edge }
    val requiredEdge = Layout.clearZoneDegrees + Layout.clearGapDegrees
    val edgePass = name == HudPanel.GRID || (edge > requiredEdge && nearestEdge > Layout.clearZoneDegrees)
    val maxNormal = samples.maxOf { it.normal }
    val maxDistanceError = samples.maxOf { abs(it.distance - Layout.distanceMeters) }
    val pass = abs(yaw - name.yaw) <= 1 && abs(pitch - name.centerPitch) <= 1 &&
        edgePass && maxNormal <= 5 && maxDistanceError <= 0.05f
    val edgeValues = if (name == HudPanel.GRID) "" else
        " innerEdgeMedian=${decimal(edge)}° innerEdgeMin=${decimal(nearestEdge)}°"
    Log.i(Tag, "${name.name} ${verdict(pass)} samples=${samples.size} " +
        "yaw=${decimal(yaw)}° pitch=${decimal(pitch)}°$edgeValues " +
        "maxNormal=${decimal(maxNormal.toDouble())}° maxDistanceError=${decimal(maxDistanceError.toDouble(), 3)}m")
    return pass
}

private fun measure(name: HudPanel, pose: Pose, width: Float, height: Float): Measurement {
    val center = pose.translation
    val yaw = yawOf(center)
    val pitch = Math.toDegrees(atan2(center.y, hypot(center.x, center.z)).toDouble())
    val normal = Vector3.angleBetween(pose.backward, -center)
    val distance = center.length
    val innerEdge = when (name) {
        HudPanel.LEFT -> -listOf(-height / 2, height / 2).maxOf { y ->
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
        HudPanel.GRID, HudPanel.BIGMAP -> 0.0 // These panels intentionally span the center.
    }
    return Measurement(yaw, pitch, innerEdge, normal, distance)
}

private fun yawOf(point: Vector3) = Math.toDegrees(atan2(point.x, -point.z).toDouble())

private fun List<Double>.median(): Double {
    val ordered = sorted()
    return (ordered[(size - 1) / 2] + ordered[size / 2]) / 2
}

private fun decimal(value: Double, places: Int = 2) = "%.${places}f".format(Locale.ROOT, value)

private fun verdict(pass: Boolean) = if (pass) "PASS" else "FAIL"

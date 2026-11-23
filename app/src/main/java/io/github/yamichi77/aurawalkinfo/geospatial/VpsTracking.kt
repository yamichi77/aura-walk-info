package io.github.yamichi77.aurawalkinfo.geospatial

import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.xr.arcore.ArDevice
import androidx.xr.arcore.CreateGeospatialPoseFromPoseSuccess
import androidx.xr.arcore.CreatePoseFromGeospatialPoseSuccess
import androidx.xr.arcore.Geospatial
import androidx.xr.arcore.TrackingState
import androidx.xr.arcore.VpsAvailabilityAvailable
import androidx.xr.compose.platform.LocalSession
import androidx.xr.runtime.Config
import androidx.xr.runtime.GeospatialMode
import androidx.xr.runtime.Session
import androidx.xr.runtime.SessionConfigureSuccess
import androidx.xr.runtime.math.GeospatialPose
import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Vector3
import io.github.yamichi77.aurawalkinfo.heading.HeadingAxes
import io.github.yamichi77.aurawalkinfo.heading.NorthEstimate
import io.github.yamichi77.aurawalkinfo.heading.normalizeDegrees
import io.github.yamichi77.aurawalkinfo.nav.NavPoint
import io.github.yamichi77.aurawalkinfo.nav.NavState
import io.github.yamichi77.aurawalkinfo.nav.distanceTo
import io.github.yamichi77.aurawalkinfo.spatial.GpsSpatialProjector
import io.github.yamichi77.aurawalkinfo.spatial.MarkVector
import io.github.yamichi77.aurawalkinfo.spatial.SpatialProjector
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.hypot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "AuraGeo"
internal const val VPS_MAX_HORIZONTAL_METERS = 5.0
internal const val VPS_MAX_YAW_DEGREES = 5.0
internal const val VPS_RECHECK_METERS = 500.0
private const val SAMPLE_MS = 100L
private const val MAX_SAMPLE_AGE_MS = 250L

internal enum class VpsStatus {
    NO_CONSENT, IDLE, COVERAGE_PENDING, COVERAGE_UNAVAILABLE, NOT_RUNNING, TRACKING_LOST,
    LOW_ACCURACY, POSE_UNAVAILABLE, READY,
}

internal fun vpsStatus(
    consent: Boolean?, demand: Boolean, available: Boolean?, running: Boolean, tracking: Boolean,
    horizontal: Double, yaw: Double,
) = vpsRequestBlocker(consent, demand, available) ?: when {
    !tracking -> VpsStatus.TRACKING_LOST
    !running -> VpsStatus.NOT_RUNNING
    horizontal !in 0.0..VPS_MAX_HORIZONTAL_METERS || yaw !in 0.0..VPS_MAX_YAW_DEGREES -> VpsStatus.LOW_ACCURACY
    else -> VpsStatus.READY
}

private fun vpsRequestBlocker(consent: Boolean?, demand: Boolean, available: Boolean?) = when {
    consent != true -> VpsStatus.NO_CONSENT
    !demand -> VpsStatus.IDLE
    available == null -> VpsStatus.COVERAGE_PENDING
    !available -> VpsStatus.COVERAGE_UNAVAILABLE
    else -> null
}

internal fun vpsDemand(nav: NavState, receivingSignal: Boolean) =
    nav is NavState.Guiding || nav is NavState.Rerouting || receivingSignal

internal fun needsVpsCoverageCheck(previous: NavPoint?, current: NavPoint) =
    previous == null || previous.distanceTo(current) >= VPS_RECHECK_METERS

/** Compare the same head forward vector in perception space and in East-Up-South. */
internal fun vpsNorth(localForward: Vector3, eastUpSouthForward: Vector3, yawAccuracy: Double): NorthEstimate? {
    val east = eastUpSouthForward.x.toDouble()
    val north = -eastUpSouthForward.z.toDouble()
    val x = localForward.x.toDouble()
    val z = localForward.z.toDouble()
    if (!(hypot(east, north) > 1e-6 && hypot(x, z) > 1e-6)) return null
    val offset = normalizeDegrees(Math.toDegrees(atan2(east, north)) - HeadingAxes.travelBearing(x, z))
    return offset.takeIf(Double::isFinite)?.let { NorthEstimate(it, yawAccuracy) }
}

internal data class VpsFix(val north: NorthEstimate, val projector: SpatialProjector)

/** Used on the main dispatcher shared by the session and HUD. */
internal class VpsTracking {
    private var sample: VpsFix? = null
    private var sampledAt = 0L
    private var geospatial: Geospatial? = null
    private var device: ArDevice? = null

    fun fix(now: Long): VpsFix? {
        val current = sample ?: return null
        if (now - sampledAt !in 0..MAX_SAMPLE_AGE_MS) return null
        val state = geospatial?.state?.value ?: return null
        return current.takeIf { state.status(device) == VpsStatus.READY }
    }

    suspend fun run(session: Session, point: () -> NavPoint?, demand: () -> Boolean) = coroutineScope {
        val geo = try {
            Geospatial.getInstance(session)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.i(TAG, "source=GPS reason=API_UNAVAILABLE type=${error.javaClass.simpleName}")
            return@coroutineScope
        }
        var checkedAt: NavPoint? = null
        var available by mutableStateOf<Boolean?>(null)
        // The OpenXR availability query works without starting the geospatial tracker.
        launch {
            snapshotFlow(point).filterNotNull().filter { needsVpsCoverageCheck(checkedAt, it) }.collectLatest { current ->
                available = null
                checkedAt = current
                val result = try {
                    withTimeoutOrNull(10_000L) { geo.checkVpsAvailability(current.latitude, current.longitude) }
                        ?.let { (it is VpsAvailabilityAvailable) to it.javaClass.simpleName } ?: (false to "TIMEOUT")
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    false to error.javaClass.simpleName
                }
                available = result.first
                Log.i(TAG, "availability=${result.second}")
            }
        }
        snapshotFlow { vpsRequestBlocker(true, demand(), available) }.collectLatest { blocker ->
            if (blocker == null) track(session, geo)
            else Log.i(TAG, "source=GPS reason=$blocker")
        }
    }

    private suspend fun track(session: Session, geo: Geospatial) {
        try {
            if (!configure(session, GeospatialMode.SPATIAL)) {
                Log.i(TAG, "source=GPS reason=CONFIGURATION_FAILED")
                return
            }
            geospatial = geo
            val head = ArDevice.getInstance(session)
            device = head
            var previous: VpsStatus? = null
            var previousState: Geospatial.GeospatialTrackingState? = null
            var nextLog = 0L
            while (true) {
                val now = SystemClock.elapsedRealtime()
                val state = geo.state.value
                var status = state.status(head)
                sample = if (status == VpsStatus.READY) sampleFix(geo, head.state.value.devicePose) else null
                if (status == VpsStatus.READY && sample == null) status = VpsStatus.POSE_UNAVAILABLE
                sampledAt = now
                if (status != previous || state.geospatialTrackingState != previousState || now >= nextLog) {
                    val source = if (sample == null) "GPS" else "VPS"
                    Log.i(TAG, String.format(
                        Locale.ROOT, "source=%s reason=%s state=%s horizontal=%.2f yaw=%.2f",
                        source, status, state.geospatialTrackingState, state.horizontalAccuracy, state.orientationYawAccuracy,
                    ))
                    previous = status
                    previousState = state.geospatialTrackingState
                    nextLog = now + 5_000L
                }
                delay(SAMPLE_MS)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.i(TAG, "source=GPS reason=TRACKER_ERROR type=${error.javaClass.simpleName}")
        } finally {
            sample = null
            geospatial = null
            device = null
            if (session.config.geospatial != GeospatialMode.DISABLED) {
                val disabled = try {
                    configure(session, GeospatialMode.DISABLED)
                } catch (error: Exception) {
                    Log.w(TAG, "disable error=${error.javaClass.simpleName}")
                    false
                }
                Log.i(TAG, "source=GPS reason=TRACKER_STOPPED disabled=$disabled")
            }
        }
    }

    private fun sampleFix(geo: Geospatial, pose: Pose): VpsFix? {
        val result = geo.createGeospatialPoseFromPose(pose) as? CreateGeospatialPoseFromPoseSuccess ?: return null
        if (result.horizontalAccuracy !in 0.0..VPS_MAX_HORIZONTAL_METERS ||
            result.orientationYawAccuracy !in 0.0..VPS_MAX_YAW_DEGREES || !result.pose.altitude.isFinite()
        ) return null
        val forward = Vector3(0f, 0f, -1f)
        val north = vpsNorth(
            pose.transformVector(forward),
            Pose(rotation = result.pose.eastUpSouthQuaternion).transformVector(forward),
            result.orientationYawAccuracy,
        ) ?: return null
        val projector = SpatialProjector { _, target, height ->
            // Keep the existing eye-height ground approximation in the SDK's WGS84 altitude frame.
            val altitude = result.pose.altitude + height - GpsSpatialProjector.EYE_HEIGHT_METERS
            val targetPose = GeospatialPose(target.latitude, target.longitude, altitude)
            val translation = try {
                (geo.createPoseFromGeospatialPose(targetPose) as? CreatePoseFromGeospatialPoseSuccess)?.pose?.translation
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                null
            }
            translation?.let { MarkVector(it.x.toDouble(), it.y.toDouble(), it.z.toDouble()) }
                ?.takeIf(MarkVector::isFinite)
        }
        return VpsFix(north, projector)
    }

    private fun Geospatial.State.status(device: ArDevice?) = vpsStatus(
        true, true, true, geospatialTrackingState == Geospatial.GeospatialTrackingState.RUNNING,
        device?.state?.value?.trackingState == TrackingState.TRACKING, horizontalAccuracy, orientationYawAccuracy,
    )

    private fun configure(session: Session, mode: GeospatialMode) = session.config.geospatial == mode ||
        session.configure(Config.Builder(session.config).setGeospatial(mode).build()) is SessionConfigureSuccess
}

@Composable
internal fun ObserveGeospatial(
    tracking: VpsTracking, eligible: Boolean, consent: Boolean?, point: NavPoint?, demand: Boolean,
) {
    val session = LocalSession.current
    val owner = LocalLifecycleOwner.current
    val latestPoint by rememberUpdatedState(point)
    val latestDemand by rememberUpdatedState(demand)
    LaunchedEffect(session, owner, tracking, eligible, consent) {
        if (!eligible || session == null) return@LaunchedEffect
        if (vpsRequestBlocker(consent, true, true) == VpsStatus.NO_CONSENT) {
            Log.i(TAG, "source=GPS reason=NO_CONSENT")
            return@LaunchedEffect
        }
        owner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            tracking.run(session, { latestPoint }, { latestDemand })
        }
    }
}

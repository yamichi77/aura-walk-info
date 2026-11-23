package io.github.yamichi77.aurawalkinfo.heading

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.xr.arcore.ArDevice
import androidx.xr.arcore.TrackingState
import androidx.xr.compose.platform.LocalSession
import androidx.xr.runtime.Config
import androidx.xr.runtime.DeviceTrackingMode
import androidx.xr.runtime.Session
import androidx.xr.runtime.SessionConfigureSuccess
import kotlin.math.atan2
import kotlin.math.hypot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

internal class HeadPoseSource {
    /** Uses the Activity's XR session and reports tracking loss between periodic samples. */
    fun poses(session: Session) = channelFlow {
        val device = try {
            if (session.config.deviceTracking != DeviceTrackingMode.SPATIAL &&
                session.configure(
                    Config.Builder(session.config).setDeviceTracking(DeviceTrackingMode.SPATIAL).build(),
                ) !is SessionConfigureSuccess
            ) return@channelFlow
            ArDevice.getInstance(session)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return@channelFlow
        }

        var originX: Double? = null
        var originZ: Double? = null
        fun sample(state: ArDevice.State): HeadPose {
            val now = SystemClock.elapsedRealtime()
            if (state.trackingState != TrackingState.TRACKING) {
                val tracking = if (state.trackingState == TrackingState.PAUSED) HeadTracking.PAUSED else HeadTracking.STOPPED
                return HeadPose(now, tracking)
            }
            val pose = state.devicePose
            val x = pose.translation.x.toDouble()
            val z = pose.translation.z.toDouble()
            if (originX == null && x.isFinite() && z.isFinite()) {
                originX = x
                originZ = z
            }
            val rotation = pose.rotation
            val qx = rotation.x.toDouble()
            val qy = rotation.y.toDouble()
            val qz = rotation.z.toDouble()
            val qw = rotation.w.toDouble()
            // Project the rotation of the canonical -Z vector onto the XZ plane.
            val sinYaw = 2 * (qx * qz + qy * qw)
            val cosYaw = 1 - 2 * (qx * qx + qy * qy)
            val yaw = Math.toDegrees(atan2(sinYaw, cosYaw)).takeIf { hypot(sinYaw, cosYaw) > 1e-6 }
            return HeadPose(
                now, HeadTracking.TRACKING, originX?.let { x - it }, originZ?.let { z - it }, yaw,
            )
        }

        launch {
            device.state.collect { state ->
                if (state.trackingState != TrackingState.TRACKING) send(sample(state))
            }
        }
        while (true) {
            send(sample(device.state.value))
            delay(100)
        }
    }
}

internal class HeadingTracking {
    private val estimator = HeadingEstimator()
    private var latestPose: HeadPose? = null

    fun onPose(pose: HeadPose) {
        latestPose = pose
        estimator.onPose(pose)
    }

    fun onFix(fix: HeadingFix) = estimator.onFix(fix)

    fun poseAt(nowMillis: Long): HeadPose? =
        latestPose?.takeIf { nowMillis - it.timeMillis in 0..HeadingEstimator.MAX_POSE_GAP_MS }

    fun estimate(nowMillis: Long): NorthEstimate? = estimator.estimate(nowMillis)

    fun clear() {
        latestPose = null
        estimator.reset()
    }
}

@Composable
internal fun ObserveHeadPoses(source: HeadPoseSource, tracking: HeadingTracking, enabled: Boolean) {
    val session = LocalSession.current
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(session, enabled, owner) {
        if (session == null || !enabled) return@LaunchedEffect
        owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                source.poses(session).collect(tracking::onPose)
            } finally {
                tracking.clear()
            }
        }
    }
}

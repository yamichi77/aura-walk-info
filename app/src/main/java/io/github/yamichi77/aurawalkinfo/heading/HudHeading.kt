package io.github.yamichi77.aurawalkinfo.heading

/** Reads T15 on the same dispatcher and monotonic clock as the pose observer. */
internal fun HeadingTracking.hudHeadBearing(nowMillis: Long): Double? =
    hudHeadBearing(poseAt(nowMillis), estimate(nowMillis))

internal fun hudHeadBearing(pose: HeadPose?, north: NorthEstimate?): Double? {
    val yaw = pose?.takeIf { it.tracking == HeadTracking.TRACKING }?.yawDegrees
        ?.takeIf { it.isFinite() } ?: return null
    return north?.takeIf { it.uncertaintyDegrees <= 15.0 }?.bearingForYaw(yaw)
}

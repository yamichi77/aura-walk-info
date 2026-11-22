package io.github.yamichi77.aurawalkinfo.settings

import kotlin.math.tan

internal object HudLayoutConfig {
    const val leftYaw = -22f
    const val rightYaw = 22f
    const val pitch = -8f
    const val distanceMeters = 1.5f
    const val clearZoneDegrees = 15f
    const val clearGapDegrees = 1f
    const val walkingDiameter = 8f
    const val stoppedDiameter = 12f

    fun textScale(dpPerMeter: Float): Float =
        (2 * distanceMeters * tan(Math.toRadians(3.5 / 2))).toFloat() * dpPerMeter / 32f
}

package io.github.yamichi77.aurawalkinfo.diagnostics

import android.content.Intent
import io.github.yamichi77.aurawalkinfo.location.AddressParts
import io.github.yamichi77.aurawalkinfo.location.AddressState
import io.github.yamichi77.aurawalkinfo.safety.MotionState
import io.github.yamichi77.aurawalkinfo.session.WalkSessionState

internal data class HudDebugOptions(
    val grid: Boolean = false,
    val selfCheck: Boolean = false,
    val capture: Boolean = false,
    val preview2d: Boolean = false,
    val fakeState: WalkSessionState? = null,
) {
    fun captureName(route: String, panel: String) = if (capture) "hud-$route-$panel.png" else null

    companion object {
        fun from(intent: Intent): HudDebugOptions {
            val selfCheck = intent.getBooleanExtra("selfcheck", false)
            val motion = when (intent.getStringExtra("fakeState")) {
                "walking" -> MotionState.WALKING
                "stopped" -> MotionState.STOPPED
                "fast" -> MotionState.FAST
                "nofix" -> MotionState.NO_FIX
                else -> if (selfCheck) MotionState.STOPPED else null
            }
            return HudDebugOptions(
                grid = intent.getBooleanExtra("grid", false),
                selfCheck = selfCheck,
                capture = intent.getBooleanExtra("capture", false),
                preview2d = intent.getBooleanExtra("preview2d", false),
                fakeState = motion?.let {
                    WalkSessionState(
                        distanceMeters = 1_240.0,
                        walkingTimeMillis = 1_122_200,
                        elapsedMillis = 1_265_000,
                        motionState = it,
                        address = AddressState.Available(AddressParts("架空県", "みほん市", "試験町", "1丁目")),
                    )
                },
            )
        }
    }
}

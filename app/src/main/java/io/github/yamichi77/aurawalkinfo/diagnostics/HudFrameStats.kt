package io.github.yamichi77.aurawalkinfo.diagnostics

import android.util.Log
import androidx.compose.runtime.withFrameNanos
import io.github.yamichi77.aurawalkinfo.nav.NavState
import java.util.Locale

private const val SECOND_NANOS = 1_000_000_000L
private const val TWO_AURA_FRAMES_NANOS = 2 * SECOND_NANOS / 72

/** Written and sampled on the main thread. Created only in debug builds. */
internal class HudFrameStats {
    var visibleMarks = 0
    var visibleSky = 0
    private var nav = "idle"

    fun updateNavigation(state: NavState) {
        nav = when (state) {
            is NavState.Preview -> "preview"
            is NavState.Guiding, is NavState.Rerouting -> "guiding"
            else -> "idle"
        }
    }

    suspend fun observeFrames() {
        var previous = withFrameNanos { it }
        var windowStart = previous
        var over28 = 0
        var longest = 0L
        while (true) {
            withFrameNanos { now ->
                val interval = now - previous
                previous = now
                if (interval > TWO_AURA_FRAMES_NANOS) {
                    over28++
                    longest = maxOf(longest, interval)
                }
                if (now - windowStart >= SECOND_NANOS) {
                    if (over28 > 0) {
                        val millis = "%.2f".format(Locale.ROOT, longest / 1_000_000.0)
                        Log.i("AuraFrame", "longestMillis=$millis over28=$over28 nav=$nav marks=$visibleMarks sky=$visibleSky")
                    }
                    windowStart = now
                    over28 = 0
                    longest = 0L
                }
            }
        }
    }
}

package io.github.yamichi77.aurawalkinfo.ui.hud

import io.github.yamichi77.aurawalkinfo.pics.PicsAspect.*
import io.github.yamichi77.aurawalkinfo.pics.PicsLight
import org.junit.Assert.assertEquals
import org.junit.Test

class PicsHudDisplayTest {
    @Test
    fun formatsLightsInOrderWithOptionalUnitlessRemaining() {
        assertEquals("赤7・青3", formatPicsAspects(listOf(PicsLight(RED, 7), PicsLight(GREEN, 3))))
        assertEquals(
            "青・青点滅4・赤",
            formatPicsAspects(listOf(PicsLight(GREEN, null), PicsLight(FLASHING_GREEN, 4), PicsLight(RED, null))),
        )
    }

    @Test
    fun ignoresUnreportableLightsBeforeLimitingTheTextToThree() {
        val unreportable = listOf(PicsLight(NONE, 4), PicsLight(OUT_OF_CONTROL, 5), PicsLight(UNKNOWN, null))
        assertEquals("情報なし", formatPicsAspects(unreportable))
        assertEquals("情報なし", formatPicsAspects(emptyList()))
        val three = listOf(PicsLight(RED, 7), PicsLight(FLASHING_GREEN, null), PicsLight(GREEN, 3))
        assertEquals("赤7・青点滅・青3", formatPicsAspects(unreportable + three))
        assertEquals("赤7・青点滅・青3…", formatPicsAspects(unreportable + three + PicsLight(RED, 1)))
    }
}

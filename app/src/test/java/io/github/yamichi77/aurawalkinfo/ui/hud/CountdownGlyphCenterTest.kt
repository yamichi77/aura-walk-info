package io.github.yamichi77.aurawalkinfo.ui.hud

import android.graphics.RectF
import androidx.compose.ui.graphics.asAndroidPath
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CountdownGlyphCenterTest {
    private fun bounds(glyph: CountdownArtwork.Glyph) = RectF().also { glyph.outline.asAndroidPath().computeBounds(it, true) }

    @Test fun eachDigitIsCenteredOnItsFilledArea() {
        for (glyph in CountdownArtwork().glyphs) {
            val path = glyph.outline.asAndroidPath()
            assertEquals("digit ${glyph.digit}", 0f, glyphMassCenterX(path, bounds(glyph)), 0.01f)
            assertEquals("digit ${glyph.digit}", 0f, bounds(glyph).centerY(), 1e-4f)
            assertEquals("digit ${glyph.digit}", 1f, bounds(glyph).height(), 1e-4f)
        }
    }

    @Test fun theFlagOfOneNoLongerPushesItsStemRight() {
        val one = CountdownArtwork().glyphs.first { it.digit == 1 }
        // The flag reaches further left than the stem reaches right, so the bounds now sit left of center.
        assertTrue(bounds(one).centerX() < -0.03f)
    }
}

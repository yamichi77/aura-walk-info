package io.github.yamichi77.aurawalkinfo.spatial

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StandingMarkArtworkTest {
    private val up = MarkVector.UP
    private val view = MarkView(MarkVector(0.0, 0.0, 0.0), up)
    private val facing = MarkVector(0.0, 0.0, 1.0)

    @Test fun onlyChangesToTheArtworkInvalidateTheView() {
        val artwork = newArtwork()
        val shadow = shadowOf(artwork)
        val base = MarkVector(0.0, 0.6, -20.0)
        val far = standingArrow(base, view, facing, MarkVector(1.0, 0.0, 0.0), false).artwork
        artwork.update(far)
        assertTrue(shadow.wasInvalidated())

        shadow.clearWasInvalidated()
        repeat(10) { artwork.update((far as MarkArtwork.Arrow).copy()) }
        assertFalse(shadow.wasInvalidated())

        artwork.update((far as MarkArtwork.Arrow).copy(near = true))
        assertTrue(shadow.wasInvalidated())
        shadow.clearWasInvalidated()

        val smaller = standingArrow(base * 0.5, view, facing, MarkVector(-1.0, 0.0, 0.0), true).artwork
        artwork.update(smaller)
        assertTrue(shadow.wasInvalidated())
    }

    @Test fun arrowDrawsCoreEdgeAndLowOpacityGlowOnATransparentPanel() {
        val data = standingArrow(MarkVector(0.0, 0.6, -20.0), view, facing, facing * -1.0, false).artwork
        val bitmap = draw(data)
        val middle = bitmap.height / 2
        val colors = (0 until bitmap.width).map { bitmap.getPixel(it, middle) }
        assertEquals(0xFFE0F7FF.toInt(), colors[bitmap.width / 2])
        assertTrue(0xFF7FDBFF.toInt() in colors)
        val glowLevels = colors.map(Color::alpha).filter { it in 1..76 }.toSet()
        assertTrue(glowLevels.size > 5)
        assertEquals(Color.TRANSPARENT, colors.first())
    }

    @Test fun destinationDrawsTheOutlineAndDotAndFadesTheShortTailBeforeClipping() {
        val near = destination(20.0)
        val nearImage = draw(near)
        fun row(y: Double, art: MarkArtwork.Destination, image: Bitmap) = (y / art.height * image.height).toInt()
        val middle = nearImage.width / 2
        val headRow = row(near.headY, near, nearImage)
        assertEquals(0xFFE0F7FF.toInt(), nearImage.getPixel(middle, headRow))
        assertEquals(Color.TRANSPARENT, nearImage.getPixel(middle + nearImage.width / 6, headRow))
        fun tailAlpha(fraction: Double): Int {
            val y = near.tipY + (near.tailEndY - near.tipY) * fraction
            return Color.alpha(nearImage.getPixel(middle, row(y, near, nearImage)))
        }
        assertTrue(tailAlpha(0.1) > 220)
        assertTrue(tailAlpha(0.9) in 100..125)

        val clipped = destination(5.0, headRise = 0.77)
        val clippedImage = draw(clipped)
        val cutoff = row(clipped.visibleHeight, clipped, clippedImage)
        val lastVisible = (0 until clippedImage.height).lastOrNull { Color.alpha(clippedImage.getPixel(middle, it)) > 0 }
        assertTrue("cutoff=$cutoff, last visible row=$lastVisible", lastVisible != null && lastVisible in cutoff - 2 until cutoff)
    }

    private fun destination(distance: Double, headRise: Double? = null): MarkArtwork.Destination {
        val ground = MarkVector(0.0, -GpsSpatialProjector.EYE_HEIGHT_METERS, -distance)
        val head = headRise?.let { MarkVector(0.0, it, -distance) } ?: destinationHeadTarget(ground, view)
        return checkNotNull(standingDestination(head, ground, view, facing)).artwork as MarkArtwork.Destination
    }

    private fun newArtwork() = StandingMarkArtwork(ApplicationProvider.getApplicationContext<Context>())

    private fun draw(data: MarkArtwork): Bitmap {
        val artwork = newArtwork().apply {
            layout(0, 0, 512, 512)
            update(data)
        }
        return Bitmap.createBitmap(artwork.width, artwork.height, Bitmap.Config.ARGB_8888).also {
            artwork.draw(Canvas(it))
        }
    }
}

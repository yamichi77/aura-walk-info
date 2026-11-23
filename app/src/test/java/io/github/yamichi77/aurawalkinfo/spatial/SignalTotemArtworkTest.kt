package io.github.yamichi77.aurawalkinfo.spatial

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yamichi77.aurawalkinfo.pics.PicsAspect
import io.github.yamichi77.aurawalkinfo.pics.PicsLight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SignalTotemArtworkTest {
    @Test fun sameSizeDotsAndOutlineOnlyFlashingDrawOnATransparentPanel() {
        val totem = signalFixture(listOf(
            PicsLight(PicsAspect.RED, 1),
            PicsLight(PicsAspect.GREEN, 8),
            PicsLight(PicsAspect.FLASHING_GREEN, null),
        )).toSignalTotem()
        val view = SignalTotemArtwork(ApplicationProvider.getApplicationContext<Context>()).apply {
            this.totem = totem
            layout(0, 0, 640, 1060)
        }
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        fun pixel(x: Int, y: Int) = bitmap.getPixel(x, y)
        fun dotWidth(y: Int): Int {
            val visible = (0..120).filter { Color.alpha(pixel(it, y)) > 0 }
            return visible.last() - visible.first() + 1
        }
        assertTrue(dotWidth(150) >= 100)
        assertEquals(dotWidth(150), dotWidth(450))
        assertEquals(dotWidth(150), dotWidth(750))
        assertTrue(Color.alpha(pixel(65, 150)) in 1..77)
        assertEquals(Color.TRANSPARENT, pixel(65, 750))
        assertEquals(Color.TRANSPARENT, pixel(0, 450))
        for ((center, segments) in listOf(150 to 1, 450 to 8, 750 to 0)) {
            assertEquals(segments, (0..7).count { pixel(524 + it * 13, center) == totem.textColor })
        }
    }
}

package io.github.yamichi77.aurawalkinfo.ui.hud

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yamichi77.aurawalkinfo.AuraWalkApplication
import io.github.yamichi77.aurawalkinfo.MainActivity
import io.github.yamichi77.aurawalkinfo.pics.PicsAspect
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = AuraWalkApplication::class, qualifiers = "w600dp-h600dp-mdpi")
class SignalHudRemovalTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun activeSignalDoesNotAppearInTheLeftClusterOrEitherMapMode() {
        val app = ApplicationProvider.getApplicationContext<AuraWalkApplication>()
        app.applicationInfo.flags = app.applicationInfo.flags or ApplicationInfo.FLAG_DEBUGGABLE
        val intent = Intent(app, MainActivity::class.java)
            .putExtra("fakeState", "stopped")
            .putExtra("preview2d", true)
        val replayAction = "io.github.yamichi77.aurawalkinfo.PICS_REPLAY"
        Robolectric.buildActivity(MainActivity::class.java, intent).setup().use {
            val main = shadowOf(Looper.getMainLooper())
            main.idle()
            val replay = shadowOf(app).registeredReceivers.single { it.intentFilter.hasAction(replayAction) }
            replay.broadcastReceiver.onReceive(app, Intent(replayAction).putExtra("scenario", "spatial"))
            main.idle()
            assertEquals(
                listOf(PicsAspect.RED, PicsAspect.GREEN),
                app.container.picsReceiver.candidate.value?.lights?.map { light -> light.aspect },
            )

            compose.onNodeWithText("経過", substring = true).assertIsDisplayed()
            val miniMap = compose.onNodeWithTag("map")
            miniMap.assertIsDisplayed()
            assertNoSignalLabelsOrMapMark()

            miniMap.performClick()
            compose.onNodeWithTag("bigmap").assertIsDisplayed()
            val mode = compose.onNodeWithTag("bigmap_mode")
            compose.onNodeWithText("3D").assertIsDisplayed()
            assertNoSignalLabelsOrMapMark()

            mode.performClick()
            compose.onNodeWithText("2D").assertIsDisplayed()
            compose.onNodeWithTag("bigmap").assertIsDisplayed()
            assertNoSignalLabelsOrMapMark()
        }
    }

    private fun assertNoSignalLabelsOrMapMark() {
        compose.onNodeWithText("信号", substring = true, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("赤3・青8", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("pics-map-mark", useUnmergedTree = true).assertDoesNotExist()
    }
}

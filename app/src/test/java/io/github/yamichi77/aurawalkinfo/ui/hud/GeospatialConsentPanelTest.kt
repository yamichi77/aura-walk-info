package io.github.yamichi77.aurawalkinfo.ui.hud

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yamichi77.aurawalkinfo.diagnostics.SkyBandSettings
import io.github.yamichi77.aurawalkinfo.settings.HudSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GeospatialConsentPanelTest {
    @get:Rule val compose = createComposeRule()

    @Test fun allowingDismissesAndPersists() = verifyAnswer("使う", true)

    @Test fun decliningDismissesAndPersists() = verifyAnswer("使わない", false)

    private fun verifyAnswer(label: String, expected: Boolean) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val storage = HudSettings(context).also { it.geospatialConsent = null }
        val settings = mutableStateOf(SkyBandSettings(storage))
        var openedUrl: String? = null
        compose.setContent {
            CompositionLocalProvider(LocalUriHandler provides object : UriHandler {
                override fun openUri(uri: String) { openedUrl = uri }
            }) {
                Box(Modifier.size(105.dp, 126.dp).testTag("left-cluster")) {
                    GeospatialConsentPanel(settings.value, 1f, true)
                }
            }
        }
        compose.onNodeWithText(GEOSPATIAL_NOTICE).assertIsDisplayed()
        compose.onNodeWithText("使う").assertIsDisplayed()
        val decline = compose.onNodeWithText("使わない").assertIsDisplayed().getUnclippedBoundsInRoot()
        val panel = compose.onNodeWithTag("left-cluster").getUnclippedBoundsInRoot()
        assertTrue("Consent buttons must fit in the left panel", decline.bottom <= panel.bottom)
        compose.onNodeWithText("詳しく見る").assertIsDisplayed().performClick()
        assertEquals("https://support.google.com/ar?p=how-google-play-services-for-ar-handles-your-data", openedUrl)
        assertNull(storage.geospatialConsent)
        compose.onNodeWithText(label).performClick()
        compose.onNodeWithText(GEOSPATIAL_NOTICE).assertDoesNotExist()

        compose.runOnIdle { settings.value = SkyBandSettings(HudSettings(context)) }
        assertEquals(expected, settings.value.geospatialConsent)
        compose.onNodeWithText(GEOSPATIAL_NOTICE).assertDoesNotExist()

        compose.runOnIdle {
            settings.value.applyDebugIntent(Intent().putExtra("clear_geospatial_consent", true))
        }
        assertNull(HudSettings(context).geospatialConsent)
        compose.onNodeWithText(GEOSPATIAL_NOTICE).assertIsDisplayed()
    }
}

package io.github.yamichi77.aurawalkinfo.ui.hud

import android.content.Context
import android.graphics.Color
import androidx.annotation.RawRes
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yamichi77.aurawalkinfo.R
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MapStyleTest {
    @Test
    fun miniMapShowsPoiAndStationIconsWithoutNames() {
        checkLabels(R.raw.map_style_dark, showNames = false)
    }

    @Test
    fun bigMapShowsPoiAndStationIconsWithDarkOutlinedNames() {
        checkLabels(R.raw.map_style_dark_bigmap, showNames = true)
    }

    @Test
    fun tiltedMapShowsOnlyStationLabelsAndDrawsBuildingsAsDarkOutlines() {
        val flat = loadRules(R.raw.map_style_dark_bigmap)
        val tilted = loadRules(R.raw.map_style_dark_bigmap_3d)
        for (element in listOf("labels.icon", "labels.text")) {
            for (feature in listOf("poi", "poi.business", "poi.park", "road.arterial", "transit.line")) {
                assertEquals("off", tilted.style(feature, element, "visibility"))
            }
            assertEquals("on", tilted.style("transit.station", element, "visibility"))
            assertEquals("on", tilted.style("transit.station.rail", element, "visibility"))
        }
        // The legacy style cannot separate buildings from built-up ground, so a fill lights the whole
        // ground on the additive display. Keep the fill black and draw only outlines (T20 on AURA).
        assertEquals("#000000", tilted.style("landscape.man_made", "geometry.fill", "color"))
        val stroke = tilted.style("landscape.man_made", "geometry.stroke", "color")
        assertEquals("#7A98A8", stroke)
        // Thin outlines read darker than filled road ribbons, so they may be a little brighter than
        // the thick roads, but stay well below the HUD text color #E0F7FF.
        assertTrue(luminance(requireNotNull(stroke)) < luminance("#E0F7FF") * 0.7)
    }

    private fun luminance(hex: String): Double {
        val color = Color.parseColor(hex)
        return 0.2126 * Color.red(color) + 0.7152 * Color.green(color) + 0.0722 * Color.blue(color)
    }

    private fun loadRules(@RawRes resource: Int): JSONArray {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return context.resources.openRawResource(resource).bufferedReader().use { JSONArray(it.readText()) }
    }

    private fun checkLabels(@RawRes resource: Int, showNames: Boolean) {
        val rules = loadRules(resource)
        for (feature in listOf("poi.business", "transit.station.rail")) {
            assertEquals("on", rules.style(feature, "labels.icon", "visibility"))
            assertEquals(if (showNames) "on" else "off", rules.style(feature, "labels.text", "visibility"))
            if (showNames) {
                assertEquals("#9FB8C8", rules.style(feature, "labels.text.fill", "color"))
                assertEquals("#000000", rules.style(feature, "labels.text.stroke", "color"))
            }
        }
        for (feature in listOf("road.arterial", "administrative.locality", "transit.line")) {
            assertEquals("off", rules.style(feature, "labels.icon", "visibility"))
            assertEquals("off", rules.style(feature, "labels.text", "visibility"))
        }
        assertEquals("off", rules.style("transit.line", "geometry", "visibility"))
    }

    private fun JSONArray.style(feature: String, element: String, property: String): String? {
        var value: String? = null
        for (index in 0 until length()) {
            val rule = getJSONObject(index)
            if (!matches(feature, rule.optString("featureType", "all"))) continue
            if (!matches(element, rule.optString("elementType", "all"))) continue
            val stylers = rule.getJSONArray("stylers")
            for (stylerIndex in 0 until stylers.length()) {
                val styler = stylers.getJSONObject(stylerIndex)
                if (styler.has(property)) value = styler.getString(property)
            }
        }
        return value
    }

    private fun matches(value: String, selector: String) =
        selector == "all" || value == selector || value.startsWith("$selector.")
}

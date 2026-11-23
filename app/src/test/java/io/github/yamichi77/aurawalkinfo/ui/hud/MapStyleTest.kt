package io.github.yamichi77.aurawalkinfo.ui.hud

import android.content.Context
import androidx.annotation.RawRes
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yamichi77.aurawalkinfo.R
import org.json.JSONArray
import org.junit.Assert.assertEquals
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

    private fun checkLabels(@RawRes resource: Int, showNames: Boolean) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val rules = context.resources.openRawResource(resource).bufferedReader().use { JSONArray(it.readText()) }
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

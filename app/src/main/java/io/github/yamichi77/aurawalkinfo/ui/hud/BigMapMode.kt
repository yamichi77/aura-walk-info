package io.github.yamichi77.aurawalkinfo.ui.hud

import androidx.annotation.RawRes
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import io.github.yamichi77.aurawalkinfo.R
import io.github.yamichi77.aurawalkinfo.session.MapCenter

internal enum class BigMapMode(
    val zoom: Float,
    val minZoom: Float,
    val maxZoom: Float,
    val tilt: Float,
    val buildingsEnabled: Boolean,
    @get:RawRes val style: Int,
    val toggleLabel: String,
) {
    FLAT(16.5f, 14f, 19f, 0f, false, R.raw.map_style_dark_bigmap, "3D"),
    TILTED(18f, 16f, 20f, 60f, true, R.raw.map_style_dark_bigmap_3d, "2D");

    fun cameraPosition(center: MapCenter, zoom: Float = this.zoom) =
        CameraPosition(LatLng(center.latitude, center.longitude), zoom.coerceIn(minZoom, maxZoom), tilt, 0f)
}

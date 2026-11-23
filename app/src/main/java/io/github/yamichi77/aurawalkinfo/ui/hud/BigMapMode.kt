package io.github.yamichi77.aurawalkinfo.ui.hud

import androidx.annotation.RawRes
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import io.github.yamichi77.aurawalkinfo.R
import io.github.yamichi77.aurawalkinfo.session.MapCenter

internal enum class BigMapMode(
    val zoom: Float,
    val tilt: Float,
    val buildingsEnabled: Boolean,
    @get:RawRes val style: Int,
    val toggleLabel: String,
) {
    FLAT(16.5f, 0f, false, R.raw.map_style_dark_bigmap, "3D"),
    TILTED(17.5f, 60f, true, R.raw.map_style_dark_bigmap_3d, "2D");

    fun cameraPosition(center: MapCenter) =
        CameraPosition(LatLng(center.latitude, center.longitude), zoom, tilt, 0f)
}

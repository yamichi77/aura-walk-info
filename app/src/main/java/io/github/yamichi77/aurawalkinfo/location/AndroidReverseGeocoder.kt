package io.github.yamichi77.aurawalkinfo.location

import android.content.Context
import android.location.Address
import android.location.Geocoder
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

internal class AndroidReverseGeocoder(context: Context) : ReverseGeocoder {
    private val geocoder = Geocoder(context, Locale.JAPAN)

    override suspend fun lookup(latitude: Double, longitude: Double): AddressParts? {
        if (!Geocoder.isPresent()) return null
        return suspendCancellableCoroutine { continuation ->
            val listener = object : Geocoder.GeocodeListener {
                override fun onGeocode(addresses: MutableList<Address>) {
                    continuation.resume(addresses.firstOrNull()?.toJapaneseAddressParts())
                }

                override fun onError(errorMessage: String?) {
                    continuation.resume(null)
                }
            }

            try {
                geocoder.getFromLocation(latitude, longitude, 1, listener)
            } catch (_: IOException) {
                continuation.resume(null)
            } catch (_: IllegalArgumentException) {
                continuation.resume(null)
            }
        }
    }
}

private val addressPrefix = Regex("""^(?:日本[、,\s]*)?(?:〒?\s*\d{3}-\d{4}[、,\s]*)?""")
private val prefecturePrefix = Regex("""^(?:東京都|北海道|(?:京都|大阪)府|[^\s、,]{2,4}県)""")
private val municipalityPrefix = Regex("""^(?:[^\s、,]+?郡[^\s、,]+?[町村]|[^\s、,]+?市(?:[^\s、,市]+?区)?|[^\s、,]+?[区町村])""")
private val wardPrefix = Regex("""^[^\s、,]+?区""")
private val chomePattern = Regex("""[0-9一二三四五六七八九十百]+丁目""")
private val lotNumber = Regex("""[0-9]+(?:[-－−‐・の][0-9]+|番|号|$).*""")

internal fun Address.toJapaneseAddressParts(): AddressParts? {
    // adminArea is the prefecture. Strip country and postal code before using line 0 as a fallback.
    val line = getAddressLine(0)?.trim()?.withHalfWidthDigits()?.replaceFirst(addressPrefix, "").orEmpty()
    val prefecture = adminArea?.trim()?.takeIf(String::isNotEmpty) ?: prefecturePrefix.find(line)?.value
    val afterPrefecture = line.removePrefix(prefecture.orEmpty()).trimStart()

    // locality is preferred for the municipality; subAdminArea may contain a municipality or county.
    val subAdmin = subAdminArea?.trim().orEmpty()
    val local = locality?.trim().orEmpty()
    val subLocal = subLocality?.trim()?.withHalfWidthDigits().orEmpty()
    val street = thoroughfare?.trim()?.withHalfWidthDigits().orEmpty()
    val cityBase = when {
        subAdmin.endsWith("郡") && (local.endsWith("町") || local.endsWith("村")) -> subAdmin + local
        subAdmin.endsWith("市") && local.endsWith("区") -> subAdmin + local
        local.isNotEmpty() -> local
        subAdmin.endsWith("市") || subAdmin.endsWith("区") || subAdmin.endsWith("町") || subAdmin.endsWith("村") -> subAdmin
        else -> municipalityPrefix.find(afterPrefecture)?.value.orEmpty()
    }
    val ward = if (cityBase.endsWith("市")) {
        sequenceOf(subLocal, street, afterPrefecture.removePrefix(cityBase))
            .mapNotNull { wardPrefix.find(it.trimStart())?.value }
            .firstOrNull().orEmpty()
    } else {
        ""
    }
    val city = cityBase + ward
    val cityWard = ward.ifEmpty {
        city.substringAfterLast('市', "").takeIf { it.endsWith("区") }.orEmpty()
    }

    // subLocality and thoroughfare can split the town and chome differently. Match line 0
    // against the administrative prefix before using it to fill a missing town or chome.
    val neighborhood = afterPrefecture.removePrefix(city).trimStart()
        .takeIf { city.isNotEmpty() && afterPrefecture.startsWith(city) }.orEmpty()
    val candidates = listOf(subLocal, street, neighborhood).map {
        it.removePrefix(city).removePrefix(cityWard).trimStart()
    }
    val town = candidates.firstNotNullOfOrNull { candidate ->
        lotNumber.replace(candidate.substringBeforeChome(), "").trim()
            .takeIf(String::isNotEmpty)
    }
    val chome = candidates.firstNotNullOfOrNull { chomePattern.find(it)?.value }
    val parts = AddressParts(prefecture, city.ifEmpty { null }, town, chome)
    return parts.takeIf { it.prefecture != null || it.city != null || it.town != null || it.chome != null }
}

private fun String.substringBeforeChome(): String {
    val chome = chomePattern.find(this) ?: return this
    return substring(0, chome.range.first)
}

package io.github.yamichi77.aurawalkinfo.location

import android.location.Address
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidReverseGeocoderTest {
    @Test
    fun extractsStructuredWardAndFillsChomeFromAddressLine() {
        val address = Address(Locale.JAPAN).apply {
            adminArea = "神奈川県"
            locality = "架空市"
            subLocality = "架空区"
            thoroughfare = "試験町"
            setAddressLine(0, "日本、〒000-0000 神奈川県架空市架空区試験町２丁目３番４号")
        }
        assertEquals(
            AddressParts("神奈川県", "架空市架空区", "試験町", "2丁目"),
            address.toJapaneseAddressParts(),
        )
    }

    @Test
    fun recoversMissingFieldsFromAddressLineAndDropsLotNumbers() {
        val address = Address(Locale.JAPAN).apply {
            setAddressLine(0, "〒000-0000 東京都架空区試験町３−４")
        }
        assertEquals(AddressParts("東京都", "架空区", "試験町"), address.toJapaneseAddressParts())
    }
}

package io.github.yamichi77.aurawalkinfo.location

import org.junit.Assert.assertEquals
import org.junit.Test

class JapaneseAddressFormatterTest {
    @Test
    fun composesOneAndTwoLinesAndNormalizesDigits() {
        val parts = AddressParts("東京都", "架空区", "架空町", "１丁目")
        assertEquals("架空区架空町1丁目", JapaneseAddressFormatter.oneLine(parts))
        assertEquals(listOf("東京都架空区", "架空町1丁目"), JapaneseAddressFormatter.twoLines(parts))
    }

    @Test
    fun clipsOnlyAfterTenCharactersAndSkipsMissingFields() {
        assertEquals("甲乙丙丁戊己庚辛壬癸", JapaneseAddressFormatter.oneLine(AddressParts(town = "甲乙丙丁戊己庚辛壬癸")))
        assertEquals("甲乙丙丁戊己庚辛壬…", JapaneseAddressFormatter.oneLine(AddressParts(town = "甲乙丙丁戊己庚辛壬癸子")))
        assertEquals(listOf("架空町"), JapaneseAddressFormatter.twoLines(AddressParts(town = "架空町")))
        assertEquals(emptyList<String>(), JapaneseAddressFormatter.twoLines(AddressParts()))
    }
}

package io.github.yamichi77.aurawalkinfo.pics

import io.github.yamichi77.aurawalkinfo.session.MapCenter
import java.io.File
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PicsParserTest {
    private val parser = PicsParser(debuggable = false)

    private fun packet(type: Int, body: ByteArray) = ByteBuffer.allocate(10 + body.size)
        .put(2, type.toByte())
        .putInt(6, 0x0BADB002)
        .apply { position(10) }
        .put(body)
        .array()

    @Test
    fun decodesUtf8AndBigEndianIntersectionId() {
        val data = packet(0, "試験\u0000A\u0000\u0000".encodeToByteArray())
        assertEquals(PicsPacket.Identifier("0BADB002", "試験\u0000A"), parser.parse(PICS_COMPANY_ID, data))
    }

    @Test
    fun decodesSignedCoordinates() {
        val body = ByteBuffer.allocate(8).putInt(-12_345_678).putInt(-123_456_789).array()
        assertEquals(
            PicsPacket.Position("0BADB002", MapCenter(-12.345678, -123.456789)),
            parser.parse(PICS_COMPANY_ID, packet(1, body)),
        )
    }

    @Test
    fun decodesSixteenLightsIncludingUnknownAspectsAndRemainingValues() {
        val body = ByteArray(16) { index -> ((index shl 4) or (index % 6)).toByte() }
        val signal = parser.parse(PICS_COMPANY_ID, packet(2, body)) as PicsPacket.Signal
        val aspects = listOf(
            PicsAspect.NONE, PicsAspect.RED, PicsAspect.FLASHING_GREEN,
            PicsAspect.GREEN, PicsAspect.OUT_OF_CONTROL, PicsAspect.UNKNOWN,
        )
        assertEquals(16, signal.lights.size)
        signal.lights.forEachIndexed { index, light ->
            assertEquals(aspects[index % 6], light.aspect)
            assertEquals(if (index < 8) index + 1 else null, light.remaining)
        }
        assertEquals(signal, parser.parse(PICS_COMPANY_ID, packet(2, body + byteArrayOf(0x01))))
    }

    @Test
    fun ignoresTruncatedUnknownAndOutOfRangePackets() {
        assertNull(parser.parse(PICS_COMPANY_ID, ByteArray(9)))
        assertNull(parser.parse(PICS_COMPANY_ID, packet(0, byteArrayOf())))
        assertNull(parser.parse(PICS_COMPANY_ID, packet(1, ByteArray(7))))
        assertNull(parser.parse(PICS_COMPANY_ID, packet(2, byteArrayOf())))
        assertNull(parser.parse(PICS_COMPANY_ID, packet(3, byteArrayOf(1))))
        val invalidLatitude = ByteBuffer.allocate(8).putInt(90_000_001).putInt(0).array()
        assertNull(parser.parse(PICS_COMPANY_ID, packet(1, invalidLatitude)))
    }

    @Test
    fun testCompanyIdIsAcceptedOnlyInDebugConfiguration() {
        val data = packet(2, byteArrayOf(1))
        val debug = PicsParser(debuggable = true)
        assertEquals(listOf(0x01CE), parser.companyIds)
        assertEquals(listOf(0x01CE, 0xFFFF), debug.companyIds)
        assertNull(parser.parse(0xFFFF, data))
        assertNotNull(debug.parse(0xFFFF, data))
        assertNotNull(debug.parse(0x01CE, data))
        assertNull(debug.parse(0x0000, data))
    }

    @Test
    fun mainSourcesHaveNoBleConnectionOrAdvertisingApis() {
        val sourceRoot = listOf(File("src/main"), File("app/src/main")).firstOrNull { it.isDirectory }
            ?: error("Cannot locate app/src/main")
        val sources = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension in setOf("kt", "java") }.toList()
        assertTrue(sources.isNotEmpty())
        val forbidden = listOf("connectGatt", "BluetoothLeAdvertiser", "startAdvertising")
        sources.forEach { file ->
            val source = file.readText()
            forbidden.forEach { api ->
                assertTrue("${file.relativeTo(sourceRoot)} references $api", api !in source)
            }
        }
    }
}

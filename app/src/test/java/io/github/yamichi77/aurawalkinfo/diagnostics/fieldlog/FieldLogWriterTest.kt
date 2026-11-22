package io.github.yamichi77.aurawalkinfo.diagnostics.fieldlog

import io.github.yamichi77.aurawalkinfo.location.FixDecision
import io.github.yamichi77.aurawalkinfo.safety.MotionState
import io.github.yamichi77.aurawalkinfo.session.SessionMetrics
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FieldLogWriterTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun writesOnlyExpectedFieldsAndFlushesFinalPartialBatchIntoSeparateSessionFiles() = runBlocking {
        val directory = temp.root.resolve("fieldlog")
        val writer = FieldLogWriter(directory)
        val row = SessionMetrics(
            1, MotionState.WALKING, 1.31234567, 5.234567f, 6.789123, FixDecision.ACCEPTED,
            distanceMeters = 35.68123456, walkingTimeMillis = 1_000L, bearingDeg = 139.76581234f,
        )
        writer.write(FieldLogProvider.GPS, (1L..11).map { row.copy(elapsedSeconds = it) }.asFlow())
        writer.write(
            FieldLogProvider.FUSED,
            flowOf(row.copy(motion = MotionState.NO_FIX, accuracyMeters = null, fixDecision = null)),
        )

        val files = directory.listFiles()!!.sortedBy { it.name }
        assertEquals(2, files.size)
        val allRows = files.map { it.readLines() }.sortedByDescending { it.size }
        val header = "elapsed_s,state,avg_speed_mps,accuracy_m,added_distance_m,fix_decision,provider"
        assertEquals(header, allRows[0].first())
        assertEquals(12, allRows[0].size)
        assertEquals("11,WALKING,1.312,5.235,6.789,ACCEPTED,gps", allRows[0].last())
        assertEquals(listOf(header, "1,NO_FIX,1.312,,6.789,NO_SAMPLE,fused"), allRows[1])
        val coordinate = Regex("""[+-]?\d{1,3}\.\d{4,}""")
        for (line in allRows.flatten()) {
            assertFalse(coordinate.containsMatchIn(line))
            assertTrue(line.split(',').size == 7)
        }
    }
}

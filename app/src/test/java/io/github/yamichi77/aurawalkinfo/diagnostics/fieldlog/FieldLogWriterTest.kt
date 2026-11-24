package io.github.yamichi77.aurawalkinfo.diagnostics.fieldlog

import io.github.yamichi77.aurawalkinfo.heading.HeadPose
import io.github.yamichi77.aurawalkinfo.heading.HeadTracking
import io.github.yamichi77.aurawalkinfo.heading.HeadingFix
import io.github.yamichi77.aurawalkinfo.heading.NorthEstimate

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
        val header = "elapsed_s,state,avg_speed_mps,accuracy_m,added_distance_m,fix_decision,provider," +
            "ar_tracking,ar_x_m,ar_z_m,ar_yaw_deg,gps_bearing_deg,gps_bearing_acc_deg,gps_speed_mps," +
            "north_offset_deg,north_uncertainty_deg"
        assertEquals(header, allRows[0].first())
        assertEquals(12, allRows[0].size)
        assertEquals("11,WALKING,1.312,5.235,6.789,ACCEPTED,gps,,,,,,,,,", allRows[0].last())
        assertEquals(listOf(header, "1,NO_FIX,1.312,,6.789,NO_SAMPLE,fused,,,,,,,,,"), allRows[1])
        val coordinate = Regex("""[+-]?\d{1,3}\.\d{4,}""")
        for (line in allRows.flatten()) {
            assertFalse(coordinate.containsMatchIn(line))
            assertTrue(line.split(',').size == 16)
        }
    }

    @Test
    fun writesIntervalPoseGpsAndNorthWithBlanksForMissingMeasurements() = runBlocking {
        val base = SessionMetrics(1, MotionState.WALKING, 1.0, 4f, 0.0, null, 0.0, 0, 10f)
        val tracked = base.copy(
            headPose = HeadPose(990, HeadTracking.TRACKING, -0.25, 1.23456, -18.0),
            headingFix = HeadingFix(950, 37.0, 9.25, 1.2),
            north = NorthEstimate(38.0, 5.1),
        )
        val paused = base.copy(
            elapsedSeconds = 2,
            headPose = HeadPose(1_990, HeadTracking.PAUSED),
            headingFix = HeadingFix(1_950, 40.0, null, null),
        )
        FieldLogWriter(temp.root).write(FieldLogProvider.GPS, flowOf(tracked, paused))
        val lines = temp.root.listFiles()!!.single().readLines()
        assertEquals(
            listOf("TRACKING", "-0.250", "1.235", "-18.000", "37.000", "9.250", "1.200", "38.000", "5.100"),
            lines[1].split(',').drop(7),
        )
        assertEquals(listOf("PAUSED", "", "", "", "40.000", "", "", "", ""), lines[2].split(',').drop(7))
    }
}

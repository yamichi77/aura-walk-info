package io.github.yamichi77.aurawalkinfo.diagnostics.fieldlog

import io.github.yamichi77.aurawalkinfo.session.SessionMetrics
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

internal enum class FieldLogProvider { FUSED, GPS }

internal class FieldLogWriter(private val directory: File) {
    suspend fun write(provider: FieldLogProvider, rows: Flow<SessionMetrics>) = withContext(Dispatchers.IO) {
        if (!directory.mkdirs() && !directory.isDirectory) throw IOException("Could not create fieldlog directory")
        newFile().bufferedWriter().use { writer ->
            writer.appendLine(
                "elapsed_s,state,avg_speed_mps,accuracy_m,added_distance_m,fix_decision,provider," +
                    "ar_tracking,ar_x_m,ar_z_m,ar_yaw_deg,gps_bearing_deg,gps_bearing_acc_deg,gps_speed_mps," +
                    "north_offset_deg,north_uncertainty_deg",
            )
            var pendingRows = 0
            rows.collect { row ->
                writer.appendLine(
                    listOf(
                        row.elapsedSeconds,
                        row.motion.name,
                        decimal(row.averageSpeedMps),
                        decimal(row.accuracyMeters?.toDouble()),
                        decimal(row.addedDistanceMeters),
                        row.fixDecision?.name ?: "NO_SAMPLE",
                        provider.name.lowercase(Locale.ROOT),
                        row.headPose?.tracking?.name.orEmpty(),
                        decimal(row.headPose?.xMeters),
                        decimal(row.headPose?.zMeters),
                        decimal(row.headPose?.yawDegrees),
                        decimal(row.headingFix?.bearingDegrees),
                        decimal(row.headingFix?.bearingAccuracyDegrees),
                        decimal(row.headingFix?.speedMps),
                        decimal(row.north?.offsetDegrees),
                        decimal(row.north?.uncertaintyDegrees),
                    ).joinToString(","),
                )
                pendingRows++
                if (pendingRows == 10) {
                    writer.flush()
                    pendingRows = 0
                }
            }
        }
    }

    private fun newFile(): File {
        val startedAt = startFormat.format(Instant.now())
        var file = File(directory, "$startedAt.csv")
        var suffix = 0
        while (!file.createNewFile()) {
            suffix++
            file = File(directory, "${startedAt}_$suffix.csv")
        }
        return file
    }

    private fun decimal(value: Double?): String =
        value?.takeIf(Double::isFinite)?.let { String.format(Locale.ROOT, "%.3f", it) }.orEmpty()

    private companion object {
        val startFormat: DateTimeFormatter =
            DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmssSSS'Z'", Locale.ROOT).withZone(ZoneOffset.UTC)
    }
}

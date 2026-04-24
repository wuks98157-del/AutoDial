package com.autodial.ui.history

import android.content.Context
import com.autodial.data.db.entity.RunRecord
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dump `RunRecord` history to a CSV file in the app's external files dir so
 * testers can pull it without needing adb + logcat. File path is returned so
 * the caller can show a toast with the location.
 *
 * Location: `Android/data/com.autodial/files/history-YYYYMMDD-HHMMSS.csv`.
 * Visible in any file manager under Internal Storage → Android → data →
 * com.autodial → files; or via `adb pull /sdcard/Android/data/com.autodial/files/`.
 */
object HistoryExporter {

    fun exportToFile(context: Context, runs: List<RunRecord>): File {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val file = File(dir, "history-$stamp.csv")
        file.writeText(buildCsv(runs))
        return file
    }

    /** Pure function — safe to unit-test without any Android dependency. */
    fun buildCsv(runs: List<RunRecord>): String {
        val sb = StringBuilder()
        sb.append("id,started_at,ended_at,duration_s,number,target,planned_cycles,completed_cycles,hangup_s,status,failure_reason\n")
        val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
        for (r in runs) {
            val duration = ((r.endedAt - r.startedAt) / 1000L).coerceAtLeast(0L)
            val target = when (r.targetPackage) {
                "com.b3networks.bizphone" -> "BizPhone"
                "finarea.MobileVoip" -> "Mobile VOIP"
                else -> r.targetPackage
            }
            sb.append(r.id).append(',')
            sb.append(dateFmt.format(Date(r.startedAt))).append(',')
            sb.append(dateFmt.format(Date(r.endedAt))).append(',')
            sb.append(duration).append(',')
            sb.append(csvEscape(r.number)).append(',')
            sb.append(target).append(',')
            sb.append(r.plannedCycles).append(',')
            sb.append(r.completedCycles).append(',')
            sb.append(r.hangupSeconds).append(',')
            sb.append(r.status.name).append(',')
            sb.append(csvEscape(r.failureReason ?: "")).append('\n')
        }
        return sb.toString()
    }

    private fun csvEscape(s: String): String =
        if (s.isEmpty()) "" else if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' })
            "\"" + s.replace("\"", "\"\"") + "\""
        else s
}

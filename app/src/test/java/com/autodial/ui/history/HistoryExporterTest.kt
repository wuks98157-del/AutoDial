package com.autodial.ui.history

import com.autodial.data.db.entity.RunRecord
import com.autodial.data.db.entity.RunStatus
import org.junit.Assert.*
import org.junit.Test

class HistoryExporterTest {

    private fun run(
        id: Long = 1,
        number: String = "67773777",
        target: String = "com.b3networks.bizphone",
        planned: Int = 3,
        completed: Int = 3,
        hangup: Int = 10,
        status: RunStatus = RunStatus.DONE,
        failure: String? = null,
        startedAt: Long = 1_745_000_000_000L,  // some fixed epoch millis
        endedAt: Long = 1_745_000_060_000L,   // +60s
    ) = RunRecord(
        id = id, startedAt = startedAt, endedAt = endedAt,
        number = number, targetPackage = target,
        plannedCycles = planned, completedCycles = completed,
        hangupSeconds = hangup, status = status, failureReason = failure
    )

    @Test fun emptyListProducesHeaderOnly() {
        val csv = HistoryExporter.buildCsv(emptyList())
        val lines = csv.lines().filter { it.isNotEmpty() }
        assertEquals(1, lines.size)
        assertTrue(lines[0].startsWith("id,started_at,ended_at,"))
    }

    @Test fun oneRunRendersHeaderPlusOneLine() {
        val csv = HistoryExporter.buildCsv(listOf(run()))
        val lines = csv.lines().filter { it.isNotEmpty() }
        assertEquals(2, lines.size)
    }

    @Test fun columnCountMatchesHeader() {
        val csv = HistoryExporter.buildCsv(listOf(run()))
        val lines = csv.lines().filter { it.isNotEmpty() }
        val headerCols = lines[0].split(',').size
        val bodyCols = lines[1].split(',').size
        assertEquals(headerCols, bodyCols)
        assertEquals(11, headerCols)  // id + 10 fields
    }

    @Test fun targetPackageIsHumanized() {
        val biz = HistoryExporter.buildCsv(listOf(run(target = "com.b3networks.bizphone")))
        val voip = HistoryExporter.buildCsv(listOf(run(target = "finarea.MobileVoip")))
        val other = HistoryExporter.buildCsv(listOf(run(target = "com.example.unknown")))
        assertTrue("BizPhone humanization", biz.contains(",BizPhone,"))
        assertTrue("Mobile VOIP humanization", voip.contains(",Mobile VOIP,"))
        assertTrue("unknown target passed through", other.contains(",com.example.unknown,"))
    }

    @Test fun durationInSecondsIsIncluded() {
        val csv = HistoryExporter.buildCsv(listOf(
            run(startedAt = 1_000_000L, endedAt = 1_045_000L)  // 45s apart
        ))
        val line = csv.lines().first { !it.startsWith("id,") && it.isNotEmpty() }
        val cols = line.split(',')
        assertEquals("45", cols[3])  // duration_s
    }

    @Test fun negativeDurationIsClampedToZero() {
        val csv = HistoryExporter.buildCsv(listOf(
            run(startedAt = 2_000_000L, endedAt = 1_000_000L)  // ended before started
        ))
        val line = csv.lines().first { !it.startsWith("id,") && it.isNotEmpty() }
        val cols = line.split(',')
        assertEquals("0", cols[3])
    }

    @Test fun failureStatusIncludesReason() {
        val csv = HistoryExporter.buildCsv(listOf(
            run(status = RunStatus.FAILED, failure = "failed:timeout")
        ))
        assertTrue(csv.contains(",FAILED,failed:timeout"))
    }

    @Test fun nullFailureReasonRendersEmpty() {
        val csv = HistoryExporter.buildCsv(listOf(run(status = RunStatus.DONE, failure = null)))
        // last column should be empty — line ends with ",\n"
        val line = csv.lines().first { !it.startsWith("id,") && it.isNotEmpty() }
        assertTrue("line should end with empty failure col", line.endsWith(","))
    }

    @Test fun fieldsContainingCommasAreQuoted() {
        val csv = HistoryExporter.buildCsv(listOf(
            run(failure = "failed:blah,with,commas", status = RunStatus.FAILED)
        ))
        assertTrue(csv.contains("\"failed:blah,with,commas\""))
    }

    @Test fun fieldsContainingQuotesAreEscaped() {
        val csv = HistoryExporter.buildCsv(listOf(
            run(number = "67\"77", status = RunStatus.DONE)
        ))
        // Inner " becomes "", wrapped in quotes
        assertTrue("quote should be doubled and field wrapped",
            csv.contains("\"67\"\"77\""))
    }

    @Test fun headerRowIsExactlyTheSchema() {
        val csv = HistoryExporter.buildCsv(emptyList())
        assertEquals(
            "id,started_at,ended_at,duration_s,number,target,planned_cycles,completed_cycles,hangup_s,status,failure_reason",
            csv.lines().first()
        )
    }
}

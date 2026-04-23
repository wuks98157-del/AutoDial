package com.autodial.data.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.autodial.data.db.AutoDialDatabase
import com.autodial.data.db.entity.RunRecord
import com.autodial.data.db.entity.RunStatus
import com.autodial.data.db.entity.RunStepEvent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryDaoTest {

    private lateinit var db: AutoDialDatabase
    private lateinit var dao: HistoryDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AutoDialDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.historyDao()
    }

    @After fun tearDown() = db.close()

    private fun run(startedAt: Long = 1000L, status: RunStatus = RunStatus.DONE) = RunRecord(
        startedAt = startedAt, endedAt = startedAt + 60_000L,
        number = "67773777", targetPackage = "com.b3networks.bizphone",
        plannedCycles = 10, completedCycles = 10, hangupSeconds = 25,
        status = status, failureReason = null
    )

    @Test
    fun insertRunAndObserve() = runTest {
        dao.insertRun(run())
        val runs = dao.observeRuns().first()
        assertEquals(1, runs.size)
        assertEquals("67773777", runs[0].number)
    }

    @Test
    fun runsOrderedNewestFirst() = runTest {
        dao.insertRun(run(1000L))
        dao.insertRun(run(3000L))
        dao.insertRun(run(2000L))
        val runs = dao.observeRuns().first()
        assertEquals(listOf(3000L, 2000L, 1000L), runs.map { it.startedAt })
    }

    @Test
    fun insertStepEventLinkedToRun() = runTest {
        val runId = dao.insertRun(run())
        dao.insertStepEvent(RunStepEvent(runId = runId, cycleIndex = 0,
            stepId = "PRESS_CALL", at = 2000L, outcome = "ok:node-primary", detail = null))
        val events = dao.getStepEvents(runId)
        assertEquals(1, events.size)
        assertEquals("ok:node-primary", events[0].outcome)
    }

    @Test
    fun deleteAll() = runTest {
        dao.insertRun(run())
        dao.deleteAll()
        assertTrue(dao.observeRuns().first().isEmpty())
    }

    @Test
    fun deleteOlderThan() = runTest {
        dao.insertRun(run(startedAt = 1000L))   // endedAt = 61000
        dao.insertRun(run(startedAt = 5000L))   // endedAt = 65000
        dao.deleteOlderThan(beforeEpochMillis = 62000L)
        val runs = dao.observeRuns().first()
        assertEquals(1, runs.size)
        assertEquals(5000L, runs[0].startedAt)
    }
}

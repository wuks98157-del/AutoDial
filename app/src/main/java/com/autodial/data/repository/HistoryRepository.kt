package com.autodial.data.repository

import com.autodial.data.db.dao.HistoryDao
import com.autodial.data.db.entity.RunRecord
import com.autodial.data.db.entity.RunStepEvent
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HistoryRepository @Inject constructor(private val dao: HistoryDao) {

    fun observeRuns(): Flow<List<RunRecord>> = dao.observeRuns()

    suspend fun startRun(run: RunRecord): Long = dao.insertRun(run)

    suspend fun logStepEvent(event: RunStepEvent) = dao.insertStepEvent(event)

    suspend fun getStepEvents(runId: Long): List<RunStepEvent> = dao.getStepEvents(runId)

    suspend fun clearAll() = dao.deleteAll()

    suspend fun clearOlderThan(beforeEpochMillis: Long) = dao.deleteOlderThan(beforeEpochMillis)
}

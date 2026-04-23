package com.autodial.data.db.dao

import androidx.room.*
import com.autodial.data.db.entity.RunRecord
import com.autodial.data.db.entity.RunStepEvent
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {
    @Query("SELECT * FROM runs ORDER BY startedAt DESC")
    fun observeRuns(): Flow<List<RunRecord>>

    @Insert
    suspend fun insertRun(run: RunRecord): Long

    @Query("SELECT * FROM run_step_events WHERE runId = :runId ORDER BY at")
    suspend fun getStepEvents(runId: Long): List<RunStepEvent>

    @Insert
    suspend fun insertStepEvent(event: RunStepEvent)

    @Update
    suspend fun updateRun(run: RunRecord)

    @Query("DELETE FROM runs")
    suspend fun deleteAll()

    @Query("DELETE FROM runs WHERE endedAt < :beforeEpochMillis")
    suspend fun deleteOlderThan(beforeEpochMillis: Long)
}

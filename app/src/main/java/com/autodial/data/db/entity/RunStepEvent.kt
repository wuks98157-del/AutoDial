package com.autodial.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "run_step_events",
    foreignKeys = [ForeignKey(
        entity = RunRecord::class,
        parentColumns = ["id"],
        childColumns = ["runId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("runId")]
)
data class RunStepEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val runId: Long,
    val cycleIndex: Int,
    val stepId: String,
    val at: Long,
    val outcome: String,         // "ok:node-primary" | "ok:node-fallback" | "ok:coord-fallback" | "failed:timeout" | "failed:hash-mismatch" | "failed:target-closed"
    val detail: String?
)

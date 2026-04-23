package com.autodial.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "runs")
data class RunRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val endedAt: Long,
    val number: String,
    val targetPackage: String,
    val plannedCycles: Int,      // 0 = spam mode
    val completedCycles: Int,
    val hangupSeconds: Int,
    val status: RunStatus,
    val failureReason: String?
)

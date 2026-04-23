package com.autodial.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "recipes")
data class Recipe(
    @PrimaryKey val targetPackage: String,
    val displayName: String,
    val recordedVersion: String,
    val recordedAt: Long,
    val schemaVersion: Int
)

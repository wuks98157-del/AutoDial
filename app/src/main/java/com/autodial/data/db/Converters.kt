package com.autodial.data.db

import androidx.room.TypeConverter
import com.autodial.data.db.entity.RunStatus

class Converters {
    @TypeConverter
    fun fromRunStatus(status: RunStatus): String = status.name

    @TypeConverter
    fun toRunStatus(value: String): RunStatus =
        runCatching { RunStatus.valueOf(value) }.getOrDefault(RunStatus.FAILED)
}

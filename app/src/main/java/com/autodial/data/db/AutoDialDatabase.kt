package com.autodial.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.autodial.data.db.dao.HistoryDao
import com.autodial.data.db.dao.RecipeDao
import com.autodial.data.db.entity.*

@Database(
    entities = [Recipe::class, RecipeStep::class, RunRecord::class, RunStepEvent::class],
    version = 1,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AutoDialDatabase : RoomDatabase() {
    abstract fun recipeDao(): RecipeDao
    abstract fun historyDao(): HistoryDao
}

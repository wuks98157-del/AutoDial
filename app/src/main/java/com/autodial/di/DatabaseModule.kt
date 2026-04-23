package com.autodial.di

import android.content.Context
import androidx.room.Room
import com.autodial.data.db.AutoDialDatabase
import com.autodial.data.db.dao.HistoryDao
import com.autodial.data.db.dao.RecipeDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AutoDialDatabase =
        Room.databaseBuilder(context, AutoDialDatabase::class.java, "autodial.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    @Singleton
    fun provideRecipeDao(db: AutoDialDatabase): RecipeDao = db.recipeDao()

    @Provides
    @Singleton
    fun provideHistoryDao(db: AutoDialDatabase): HistoryDao = db.historyDao()
}

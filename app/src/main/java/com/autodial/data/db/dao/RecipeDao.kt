package com.autodial.data.db.dao

import androidx.room.*
import com.autodial.data.db.entity.Recipe
import com.autodial.data.db.entity.RecipeStep
import kotlinx.coroutines.flow.Flow

@Dao
interface RecipeDao {
    @Query("SELECT * FROM recipes WHERE targetPackage = :pkg")
    fun observeRecipe(pkg: String): Flow<Recipe?>

    @Query("SELECT * FROM recipes WHERE targetPackage = :pkg")
    suspend fun getRecipe(pkg: String): Recipe?

    @Query("SELECT * FROM recipe_steps WHERE targetPackage = :pkg ORDER BY stepId")
    suspend fun getSteps(pkg: String): List<RecipeStep>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRecipe(recipe: Recipe)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSteps(steps: List<RecipeStep>)

    @Query("DELETE FROM recipes WHERE targetPackage = :pkg")
    suspend fun deleteRecipe(pkg: String)

    @Query("DELETE FROM recipe_steps WHERE targetPackage = :pkg")
    suspend fun deleteSteps(pkg: String)
}

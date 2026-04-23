package com.autodial.data.repository

import com.autodial.data.db.dao.RecipeDao
import com.autodial.data.db.entity.Recipe
import com.autodial.data.db.entity.RecipeStep
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecipeRepository @Inject constructor(private val dao: RecipeDao) {

    fun observeRecipe(targetPackage: String): Flow<Recipe?> = dao.observeRecipe(targetPackage)

    suspend fun getRecipe(targetPackage: String): Recipe? = dao.getRecipe(targetPackage)

    suspend fun getSteps(targetPackage: String): List<RecipeStep> = dao.getSteps(targetPackage)

    suspend fun saveRecipe(recipe: Recipe, steps: List<RecipeStep>) {
        dao.deleteSteps(recipe.targetPackage)
        dao.deleteRecipe(recipe.targetPackage)
        dao.upsertRecipe(recipe)
        dao.upsertSteps(steps)
    }

    suspend fun deleteRecipe(targetPackage: String) {
        dao.deleteSteps(targetPackage)
        dao.deleteRecipe(targetPackage)
    }
}

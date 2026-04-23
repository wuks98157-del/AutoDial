package com.autodial.data.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.autodial.data.db.AutoDialDatabase
import com.autodial.data.db.entity.Recipe
import com.autodial.data.db.entity.RecipeStep
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecipeDaoTest {

    private lateinit var db: AutoDialDatabase
    private lateinit var dao: RecipeDao
    private val pkg = "com.b3networks.bizphone"

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AutoDialDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.recipeDao()
    }

    @After fun tearDown() = db.close()

    private fun recipe(version: String = "1.0") =
        Recipe(pkg, "BizPhone", version, 1000L, 1)

    private fun step(stepId: String = "DIGIT_0") = RecipeStep(
        targetPackage = pkg, stepId = stepId,
        resourceId = "id/btn$stepId", text = stepId,
        className = "android.widget.Button",
        boundsRelX = 0.1f, boundsRelY = 0.5f, boundsRelW = 0.2f, boundsRelH = 0.1f,
        screenshotHashHex = "aabbccdd11223344",
        recordedOnDensityDpi = 420, recordedOnScreenW = 1080, recordedOnScreenH = 2340
    )

    @Test
    fun upsertAndObserveRecipe() = runTest {
        dao.upsertRecipe(recipe())
        assertEquals(recipe(), dao.observeRecipe(pkg).first())
    }

    @Test
    fun upsertReplacesPreviousRecipe() = runTest {
        dao.upsertRecipe(recipe("1.0"))
        dao.upsertRecipe(recipe("2.0"))
        assertEquals("2.0", dao.getRecipe(pkg)?.recordedVersion)
    }

    @Test
    fun deleteRecipeCascadesToSteps() = runTest {
        dao.upsertRecipe(recipe())
        dao.upsertSteps(listOf(step()))
        dao.deleteRecipe(pkg)
        assertTrue(dao.getSteps(pkg).isEmpty())
    }

    @Test
    fun getStepsReturnsAllUpserted() = runTest {
        dao.upsertRecipe(recipe())
        dao.upsertSteps(listOf(step("DIGIT_0"), step("DIGIT_1"), step("PRESS_CALL")))
        assertEquals(3, dao.getSteps(pkg).size)
    }
}

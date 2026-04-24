# AutoDial — Plan 1: Foundation (Project Setup + Data Layer)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bootstrap a buildable Android project with Hilt DI, Room database, and DataStore Proto settings fully wired up and tested — the foundation Plans 2–4 build on.

**Architecture:** Single-module Android app. Room stores recipes, recipe steps, run records, and run step events. DataStore Proto stores user settings. Hilt manages all DI. No real UI yet — just the data layer plus a stub MainActivity so the APK builds.

**Tech Stack:** Kotlin 2.0.21, AGP 8.7.0, Compose BOM 2024.09.03, Hilt 2.52, Room 2.6.1, DataStore Proto 1.1.1, Protobuf Kotlin Lite 4.28.2, KSP 2.0.21-1.0.28, Coroutines 1.9.0

---

## File Map

```
settings.gradle.kts
build.gradle.kts
gradle/libs.versions.toml
gradle.properties
app/build.gradle.kts
app/proguard-rules.pro
app/src/main/AndroidManifest.xml
app/src/main/res/values/strings.xml
app/src/main/res/values/themes.xml
app/src/main/proto/settings.proto
app/src/main/java/com/autodial/
  AutoDialApplication.kt
  data/db/entity/RunStatus.kt
  data/db/entity/Recipe.kt
  data/db/entity/RecipeStep.kt
  data/db/entity/RunRecord.kt
  data/db/entity/RunStepEvent.kt
  data/db/Converters.kt
  data/db/dao/RecipeDao.kt
  data/db/dao/HistoryDao.kt
  data/db/AutoDialDatabase.kt
  data/datastore/SettingsSerializer.kt
  data/repository/RecipeRepository.kt
  data/repository/HistoryRepository.kt
  data/repository/SettingsRepository.kt
  di/DatabaseModule.kt
  di/DataStoreModule.kt
  ui/MainActivity.kt
app/src/androidTest/java/com/autodial/
  HiltTestRunner.kt
  data/db/dao/RecipeDaoTest.kt
  data/db/dao/HistoryDaoTest.kt
```

---

### Task 1: Gradle Project Structure

**Files:** `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `gradle.properties`, `app/build.gradle.kts`, `app/proguard-rules.pro`

- [ ] **Step 1.1: Create `settings.gradle.kts`**

```kotlin
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "AutoDial"
include(":app")
```

- [ ] **Step 1.2: Create `build.gradle.kts` (project level)**

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.protobuf) apply false
}
```

- [ ] **Step 1.3: Create `gradle/libs.versions.toml`**

```toml
[versions]
agp = "8.7.0"
kotlin = "2.0.21"
ksp = "2.0.21-1.0.28"
composeBom = "2024.09.03"
hilt = "2.52"
hiltNavigationCompose = "1.2.0"
room = "2.6.1"
datastore = "1.1.1"
protobuf = "4.28.2"
protobufPlugin = "0.9.4"
coroutines = "1.9.0"
navigationCompose = "2.8.3"
lifecycle = "2.8.6"
coreKtx = "1.13.1"
activityCompose = "1.9.3"
splash = "1.0.1"
junit = "4.13.2"
junitExt = "1.2.1"
espresso = "3.6.1"

[libraries]
android-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
android-lifecycle-runtime = { group = "androidx.lifecycle", name = "lifecycle-runtime-ktx", version.ref = "lifecycle" }
android-lifecycle-viewmodel = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose", version.ref = "lifecycle" }
android-activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activityCompose" }
android-splash = { group = "androidx.core", name = "core-splashscreen", version.ref = "splash" }
compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
compose-ui = { group = "androidx.compose.ui", name = "ui" }
compose-ui-graphics = { group = "androidx.compose.ui", name = "ui-graphics" }
compose-ui-tooling-preview = { group = "androidx.compose.ui", name = "ui-tooling-preview" }
compose-ui-tooling = { group = "androidx.compose.ui", name = "ui-tooling" }
compose-material3 = { group = "androidx.compose.material3", name = "material3" }
compose-icons-extended = { group = "androidx.compose.material", name = "material-icons-extended" }
navigation-compose = { group = "androidx.navigation", name = "navigation-compose", version.ref = "navigationCompose" }
hilt-android = { group = "com.google.dagger", name = "hilt-android", version.ref = "hilt" }
hilt-compiler = { group = "com.google.dagger", name = "hilt-android-compiler", version.ref = "hilt" }
hilt-testing = { group = "com.google.dagger", name = "hilt-android-testing", version.ref = "hilt" }
hilt-navigation-compose = { group = "androidx.hilt", name = "hilt-navigation-compose", version.ref = "hiltNavigationCompose" }
room-runtime = { group = "androidx.room", name = "room-runtime", version.ref = "room" }
room-ktx = { group = "androidx.room", name = "room-ktx", version.ref = "room" }
room-compiler = { group = "androidx.room", name = "room-compiler", version.ref = "room" }
room-testing = { group = "androidx.room", name = "room-testing", version.ref = "room" }
datastore = { group = "androidx.datastore", name = "datastore", version.ref = "datastore" }
protobuf-kotlin-lite = { group = "com.google.protobuf", name = "protobuf-kotlin-lite", version.ref = "protobuf" }
coroutines-android = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-android", version.ref = "coroutines" }
coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "coroutines" }
junit = { group = "junit", name = "junit", version.ref = "junit" }
junit-ext = { group = "androidx.test.ext", name = "junit", version.ref = "junitExt" }
espresso = { group = "androidx.test.espresso", name = "espresso-core", version.ref = "espresso" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
hilt = { id = "com.google.dagger.hilt.android", version.ref = "hilt" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
protobuf = { id = "com.google.protobuf", version.ref = "protobufPlugin" }
```

- [ ] **Step 1.4: Create `gradle.properties`**

```properties
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official
android.nonTransitiveRClass=true
```

- [ ] **Step 1.5: Create `app/build.gradle.kts`**

```kotlin
import com.google.protobuf.gradle.id

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.protobuf)
}

android {
    namespace = "com.autodial"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.autodial"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "com.autodial.HiltTestRunner"
    }

    buildTypes {
        debug {
            isDebuggable = true
            buildConfigField("boolean", "DEV_MENU_ENABLED", "true")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("boolean", "DEV_MENU_ENABLED", "false")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    room {
        schemaDirectory("$projectDir/schemas")
    }
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:4.28.2" }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                id("java") { option("lite") }
                id("kotlin") { option("lite") }
            }
        }
    }
}

dependencies {
    implementation(libs.android.core.ktx)
    implementation(libs.android.lifecycle.runtime)
    implementation(libs.android.lifecycle.viewmodel)
    implementation(libs.android.activity.compose)
    implementation(libs.android.splash)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.extended)
    implementation(libs.navigation.compose)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.datastore)
    implementation(libs.protobuf.kotlin.lite)

    implementation(libs.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)

    androidTestImplementation(libs.junit.ext)
    androidTestImplementation(libs.espresso)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.coroutines.test)
    androidTestImplementation(libs.hilt.testing)
    kspAndroidTest(libs.hilt.compiler)
}
```

- [ ] **Step 1.6: Create `app/proguard-rules.pro`**

```proguard
-keep class com.autodial.data.db.entity.** { *; }
-keep class com.autodial.accessibility.** { *; }
-keep class com.autodial.UserSettings { *; }
-keep class com.autodial.UserSettingsKt { *; }
-dontwarn dagger.hilt.**
```

- [ ] **Step 1.7: Sync Gradle in Android Studio → File → Sync Project with Gradle Files**

Expected: `BUILD SUCCESSFUL`, no red errors in the IDE.

- [ ] **Step 1.8: Commit**

```bash
git init
git add settings.gradle.kts build.gradle.kts gradle/ gradle.properties app/build.gradle.kts app/proguard-rules.pro
git commit -m "chore: initial Gradle project with Hilt, Room, DataStore, Compose"
```

---

### Task 2: Room Entities

**Files:** `data/db/entity/RunStatus.kt`, `Recipe.kt`, `RecipeStep.kt`, `RunRecord.kt`, `RunStepEvent.kt`, `data/db/Converters.kt`

- [ ] **Step 2.1: Create `RunStatus.kt`**

```kotlin
package com.autodial.data.db.entity

enum class RunStatus { DONE, STOPPED, FAILED }
```

- [ ] **Step 2.2: Create `Recipe.kt`**

```kotlin
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
```

- [ ] **Step 2.3: Create `RecipeStep.kt`**

```kotlin
package com.autodial.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey

@Entity(
    tableName = "recipe_steps",
    primaryKeys = ["targetPackage", "stepId"],
    foreignKeys = [ForeignKey(
        entity = Recipe::class,
        parentColumns = ["targetPackage"],
        childColumns = ["targetPackage"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class RecipeStep(
    val targetPackage: String,
    val stepId: String,          // "OPEN_DIAL_PAD" | "DIGIT_0".."DIGIT_9" | "PRESS_CALL" | "HANG_UP_CONNECTED" | "HANG_UP_RINGING" | "RETURN_TO_DIAL_PAD"
    val resourceId: String?,
    val text: String?,
    val className: String?,
    val boundsRelX: Float,
    val boundsRelY: Float,
    val boundsRelW: Float,
    val boundsRelH: Float,
    val screenshotHashHex: String?,
    val recordedOnDensityDpi: Int,
    val recordedOnScreenW: Int,
    val recordedOnScreenH: Int
)
```

- [ ] **Step 2.4: Create `RunRecord.kt`**

```kotlin
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
```

- [ ] **Step 2.5: Create `RunStepEvent.kt`**

```kotlin
package com.autodial.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

@Entity(
    tableName = "run_step_events",
    foreignKeys = [ForeignKey(
        entity = RunRecord::class,
        parentColumns = ["id"],
        childColumns = ["runId"],
        onDelete = ForeignKey.CASCADE
    )]
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
```

- [ ] **Step 2.6: Create `Converters.kt`**

```kotlin
package com.autodial.data.db

import androidx.room.TypeConverter
import com.autodial.data.db.entity.RunStatus

class Converters {
    @TypeConverter
    fun fromRunStatus(status: RunStatus): String = status.name

    @TypeConverter
    fun toRunStatus(value: String): RunStatus = RunStatus.valueOf(value)
}
```

- [ ] **Step 2.7: Commit**

```bash
git add app/src/main/java/com/autodial/data/db/
git commit -m "feat: Room entities — Recipe, RecipeStep, RunRecord, RunStepEvent"
```

---

### Task 3: Room DAOs + Database

**Files:** `data/db/dao/RecipeDao.kt`, `data/db/dao/HistoryDao.kt`, `data/db/AutoDialDatabase.kt`

- [ ] **Step 3.1: Create `RecipeDao.kt`**

```kotlin
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
```

- [ ] **Step 3.2: Create `HistoryDao.kt`**

```kotlin
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

    @Query("DELETE FROM runs")
    suspend fun deleteAll()

    @Query("DELETE FROM runs WHERE endedAt < :beforeEpochMillis")
    suspend fun deleteOlderThan(beforeEpochMillis: Long)
}
```

- [ ] **Step 3.3: Create `AutoDialDatabase.kt`**

```kotlin
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
```

- [ ] **Step 3.4: Commit**

```bash
git add app/src/main/java/com/autodial/data/db/dao/ app/src/main/java/com/autodial/data/db/AutoDialDatabase.kt
git commit -m "feat: Room DAOs and AutoDialDatabase"
```

---

### Task 4: DataStore Proto Settings

**Files:** `app/src/main/proto/settings.proto`, `data/datastore/SettingsSerializer.kt`

- [ ] **Step 4.1: Create `app/src/main/proto/settings.proto`**

```proto
syntax = "proto3";

option java_package = "com.autodial";
option java_multiple_files = true;

message UserSettings {
  int32 default_hangup_seconds = 1;
  int32 default_cycles = 2;
  string default_target_package = 3;
  int32 spam_mode_safety_cap = 4;
  int32 inter_digit_delay_ms = 5;
  int32 overlay_x = 6;
  int32 overlay_y = 7;
  int64 onboarding_completed_at = 8;
  bool verbose_logging_enabled = 9;
}
```

- [ ] **Step 4.2: Sync Gradle to generate proto classes**

File → Sync Project with Gradle Files.
Expected: `UserSettings` class generated under `app/build/generated/source/proto/`.

- [ ] **Step 4.3: Create `SettingsSerializer.kt`**

```kotlin
package com.autodial.data.datastore

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import com.autodial.UserSettings
import com.google.protobuf.InvalidProtocolBufferException
import java.io.InputStream
import java.io.OutputStream

object SettingsSerializer : Serializer<UserSettings> {
    override val defaultValue: UserSettings = UserSettings.newBuilder()
        .setDefaultHangupSeconds(25)
        .setDefaultCycles(10)
        .setDefaultTargetPackage("com.b3networks.bizphone")
        .setSpamModeSafetyCap(9999)
        .setInterDigitDelayMs(400)
        .setOverlayX(0)
        .setOverlayY(200)
        .setOnboardingCompletedAt(0L)
        .setVerboseLoggingEnabled(false)
        .build()

    override suspend fun readFrom(input: InputStream): UserSettings =
        try { UserSettings.parseFrom(input) }
        catch (e: InvalidProtocolBufferException) { throw CorruptionException("Cannot read proto", e) }

    override suspend fun writeTo(t: UserSettings, output: OutputStream) = t.writeTo(output)
}
```

- [ ] **Step 4.4: Commit**

```bash
git add app/src/main/proto/ app/src/main/java/com/autodial/data/datastore/
git commit -m "feat: DataStore Proto settings with defaults"
```

---

### Task 5: Repositories

**Files:** `data/repository/RecipeRepository.kt`, `data/repository/HistoryRepository.kt`, `data/repository/SettingsRepository.kt`

- [ ] **Step 5.1: Create `RecipeRepository.kt`**

```kotlin
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
```

- [ ] **Step 5.2: Create `HistoryRepository.kt`**

```kotlin
package com.autodial.data.repository

import com.autodial.data.db.dao.HistoryDao
import com.autodial.data.db.entity.RunRecord
import com.autodial.data.db.entity.RunStepEvent
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HistoryRepository @Inject constructor(private val dao: HistoryDao) {

    fun observeRuns(): Flow<List<RunRecord>> = dao.observeRuns()

    suspend fun startRun(run: RunRecord): Long = dao.insertRun(run)

    suspend fun logStepEvent(event: RunStepEvent) = dao.insertStepEvent(event)

    suspend fun getStepEvents(runId: Long): List<RunStepEvent> = dao.getStepEvents(runId)

    suspend fun clearAll() = dao.deleteAll()

    suspend fun clearOlderThan(beforeEpochMillis: Long) = dao.deleteOlderThan(beforeEpochMillis)
}
```

- [ ] **Step 5.3: Create `SettingsRepository.kt`**

```kotlin
package com.autodial.data.repository

import androidx.datastore.core.DataStore
import com.autodial.UserSettings
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepository @Inject constructor(private val dataStore: DataStore<UserSettings>) {

    val settings: Flow<UserSettings> = dataStore.data

    suspend fun setDefaultHangupSeconds(seconds: Int) {
        dataStore.updateData { it.toBuilder().setDefaultHangupSeconds(seconds).build() }
    }

    suspend fun setDefaultCycles(cycles: Int) {
        dataStore.updateData { it.toBuilder().setDefaultCycles(cycles).build() }
    }

    suspend fun setDefaultTargetPackage(pkg: String) {
        dataStore.updateData { it.toBuilder().setDefaultTargetPackage(pkg).build() }
    }

    suspend fun setSpamModeSafetyCap(cap: Int) {
        dataStore.updateData { it.toBuilder().setSpamModeSafetyCap(cap).build() }
    }

    suspend fun setInterDigitDelayMs(ms: Int) {
        dataStore.updateData { it.toBuilder().setInterDigitDelayMs(ms).build() }
    }

    suspend fun setOverlayPosition(x: Int, y: Int) {
        dataStore.updateData { it.toBuilder().setOverlayX(x).setOverlayY(y).build() }
    }

    suspend fun markOnboardingComplete() {
        dataStore.updateData {
            it.toBuilder().setOnboardingCompletedAt(System.currentTimeMillis()).build()
        }
    }

    suspend fun setVerboseLogging(enabled: Boolean) {
        dataStore.updateData { it.toBuilder().setVerboseLoggingEnabled(enabled).build() }
    }
}
```

- [ ] **Step 5.4: Commit**

```bash
git add app/src/main/java/com/autodial/data/repository/
git commit -m "feat: RecipeRepository, HistoryRepository, SettingsRepository"
```

---

### Task 6: Hilt DI + Application + Manifest

**Files:** `di/DatabaseModule.kt`, `di/DataStoreModule.kt`, `AutoDialApplication.kt`, `AndroidManifest.xml`, `res/values/strings.xml`, `res/values/themes.xml`

- [ ] **Step 6.1: Create `di/DatabaseModule.kt`**

```kotlin
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
    fun provideRecipeDao(db: AutoDialDatabase): RecipeDao = db.recipeDao()

    @Provides
    fun provideHistoryDao(db: AutoDialDatabase): HistoryDao = db.historyDao()
}
```

- [ ] **Step 6.2: Create `di/DataStoreModule.kt`**

```kotlin
package com.autodial.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.dataStoreFile
import com.autodial.UserSettings
import com.autodial.data.datastore.SettingsSerializer
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataStoreModule {

    @Provides
    @Singleton
    fun provideSettingsDataStore(@ApplicationContext context: Context): DataStore<UserSettings> =
        DataStoreFactory.create(
            serializer = SettingsSerializer,
            produceFile = { context.dataStoreFile("user_settings.pb") }
        )
}
```

- [ ] **Step 6.3: Create `AutoDialApplication.kt`**

```kotlin
package com.autodial

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class AutoDialApplication : Application()
```

- [ ] **Step 6.4: Create `app/src/main/AndroidManifest.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.BIND_ACCESSIBILITY_SERVICE" />
    <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />
    <uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />

    <queries>
        <package android:name="com.b3networks.bizphone" />
        <package android:name="finarea.MobileVoip" />
    </queries>

    <application
        android:name=".AutoDialApplication"
        android:allowBackup="false"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/Theme.AutoDial"
        android:enableOnBackInvokedCallback="true">

        <activity
            android:name=".ui.MainActivity"
            android:exported="true"
            android:windowSoftInputMode="adjustResize">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

    </application>
</manifest>
```

- [ ] **Step 6.5: Create `app/src/main/res/values/strings.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">AutoDial</string>
    <string name="accessibility_service_description">AutoDial uses Android\'s accessibility service to tap buttons inside BizPhone and Mobile VOIP on your behalf, placing and ending VoIP calls as configured by the sales team. It only interacts with the buttons you teach it during the setup wizard and does not read or transmit any other screen content.</string>
</resources>
```

- [ ] **Step 6.6: Create `app/src/main/res/values/themes.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.AutoDial" parent="Theme.SplashScreen">
        <item name="windowSplashScreenBackground">@android:color/black</item>
        <item name="postSplashScreenTheme">@style/Theme.AutoDial.NoBar</item>
    </style>
    <style name="Theme.AutoDial.NoBar" parent="android:Theme.Material.NoTitleBar">
        <item name="android:windowBackground">@android:color/black</item>
    </style>
</resources>
```

- [ ] **Step 6.7: Create stub `ui/MainActivity.kt`**

Full UI comes in Plan 4. This stub makes the APK buildable now.

```kotlin
package com.autodial.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Text
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { Text("AutoDial — Plan 4 will replace this") }
    }
}
```

- [ ] **Step 6.8: Build to confirm no compile errors**

```bash
./gradlew :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`. APK at `app/build/outputs/apk/debug/app-debug.apk`.

- [ ] **Step 6.9: Commit**

```bash
git add app/src/main/java/com/autodial/ app/src/main/AndroidManifest.xml app/src/main/res/
git commit -m "feat: Hilt DI modules, Application class, stub MainActivity — project now builds"
```

---

### Task 7: DAO Instrumentation Tests

**Files:** `androidTest/.../HiltTestRunner.kt`, `RecipeDaoTest.kt`, `HistoryDaoTest.kt`

- [ ] **Step 7.1: Create `HiltTestRunner.kt`**

```kotlin
package com.autodial

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import dagger.hilt.android.testing.HiltTestApplication

class HiltTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader, name: String, context: Context): Application =
        super.newApplication(cl, HiltTestApplication::class.java.name, context)
}
```

- [ ] **Step 7.2: Create `RecipeDaoTest.kt`**

```kotlin
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
```

- [ ] **Step 7.3: Create `HistoryDaoTest.kt`**

```kotlin
package com.autodial.data.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.autodial.data.db.AutoDialDatabase
import com.autodial.data.db.entity.RunRecord
import com.autodial.data.db.entity.RunStatus
import com.autodial.data.db.entity.RunStepEvent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryDaoTest {

    private lateinit var db: AutoDialDatabase
    private lateinit var dao: HistoryDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AutoDialDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.historyDao()
    }

    @After fun tearDown() = db.close()

    private fun run(startedAt: Long = 1000L, status: RunStatus = RunStatus.DONE) = RunRecord(
        startedAt = startedAt, endedAt = startedAt + 60_000L,
        number = "67773777", targetPackage = "com.b3networks.bizphone",
        plannedCycles = 10, completedCycles = 10, hangupSeconds = 25,
        status = status, failureReason = null
    )

    @Test
    fun insertRunAndObserve() = runTest {
        dao.insertRun(run())
        val runs = dao.observeRuns().first()
        assertEquals(1, runs.size)
        assertEquals("67773777", runs[0].number)
    }

    @Test
    fun runsOrderedNewestFirst() = runTest {
        dao.insertRun(run(1000L))
        dao.insertRun(run(3000L))
        dao.insertRun(run(2000L))
        val runs = dao.observeRuns().first()
        assertEquals(listOf(3000L, 2000L, 1000L), runs.map { it.startedAt })
    }

    @Test
    fun insertStepEventLinkedToRun() = runTest {
        val runId = dao.insertRun(run())
        dao.insertStepEvent(RunStepEvent(runId = runId, cycleIndex = 0,
            stepId = "PRESS_CALL", at = 2000L, outcome = "ok:node-primary", detail = null))
        val events = dao.getStepEvents(runId)
        assertEquals(1, events.size)
        assertEquals("ok:node-primary", events[0].outcome)
    }

    @Test
    fun deleteAll() = runTest {
        dao.insertRun(run())
        dao.deleteAll()
        assertTrue(dao.observeRuns().first().isEmpty())
    }

    @Test
    fun deleteOlderThan() = runTest {
        dao.insertRun(run(startedAt = 1000L))   // endedAt = 61000
        dao.insertRun(run(startedAt = 5000L))   // endedAt = 65000
        dao.deleteOlderThan(beforeEpochMillis = 62000L)
        val runs = dao.observeRuns().first()
        assertEquals(1, runs.size)
        assertEquals(5000L, runs[0].startedAt)
    }
}
```

- [ ] **Step 7.4: Run tests against a connected device or emulator (API 30+)**

```bash
./gradlew :app:connectedAndroidTest --tests "com.autodial.data.db.dao.RecipeDaoTest"
./gradlew :app:connectedAndroidTest --tests "com.autodial.data.db.dao.HistoryDaoTest"
```

Expected: all 9 tests pass.

- [ ] **Step 7.5: Commit**

```bash
git add app/src/androidTest/
git commit -m "test: DAO instrumentation tests — RecipeDaoTest, HistoryDaoTest"
```

---

**Plan 1 complete.** The project builds, installs, and all database tests pass. Proceed to Plan 2 (Accessibility Engine).

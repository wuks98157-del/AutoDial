# AutoDial — Plan 3: Run Orchestration

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Prerequisite:** Plans 1 + 2 complete.

**Goal:** Implement the full run lifecycle: state machine, `RunForegroundService` (wake lock + notification), `OverlayController` (floating bubble), and `OemCompatibilityHelper`.

**Architecture:** `RunForegroundService` owns the `RunStateMachine` and a `PowerManager.WakeLock`. It communicates with `AutoDialAccessibilityService` via the companion-object singleton (in-process, same JVM). `OverlayController` is driven by state updates from the service. `OemCompatibilityHelper` is a pure utility that returns OEM-specific deep-link intents.

**Tech Stack:** Android ForegroundService (type: dataSync), WindowManager TYPE_APPLICATION_OVERLAY, Compose rendered inside WindowManager via LifecycleRegistry, PowerManager.WakeLock, Kotlin Coroutines + StateFlow

---

## File Map

```
app/src/main/java/com/autodial/
  model/
    RunState.kt
    RunCommand.kt
  service/
    RunStateMachine.kt
    RunForegroundService.kt
  overlay/
    OverlayController.kt
  oem/
    OemCompatibilityHelper.kt

app/src/main/res/
  drawable/ic_notification.xml

app/src/test/java/com/autodial/
  service/
    RunStateMachineTest.kt
  oem/
    OemCompatibilityHelperTest.kt
```

---

### Task 1: Run Model Classes

**Files:** `model/RunState.kt`, `model/RunCommand.kt`

- [ ] **Step 1.1: Create `model/RunState.kt`**

```kotlin
package com.autodial.model

sealed class RunState {
    object Idle : RunState()
    data class Preparing(val params: RunParams) : RunState()
    data class LaunchingTarget(val params: RunParams) : RunState()
    data class OpeningDialPad(val params: RunParams, val cycle: Int) : RunState()
    data class TypingDigits(val params: RunParams, val cycle: Int, val digitIndex: Int) : RunState()
    data class PressingCall(val params: RunParams, val cycle: Int) : RunState()
    data class InCall(val params: RunParams, val cycle: Int, val hangupAt: Long) : RunState()
    data class HangingUp(val params: RunParams, val cycle: Int) : RunState()
    data class ReturningToDialPad(val params: RunParams, val cycle: Int) : RunState()
    data class Completed(val runId: Long) : RunState()
    data class StoppedByUser(val runId: Long) : RunState()
    data class Failed(val runId: Long, val reason: String) : RunState()
}

fun RunState.isTerminal(): Boolean =
    this is RunState.Completed || this is RunState.StoppedByUser || this is RunState.Failed

fun RunState.isActive(): Boolean = !isTerminal() && this !is RunState.Idle
```

- [ ] **Step 1.2: Create `model/RunCommand.kt`**

```kotlin
package com.autodial.model

sealed class RunCommand {
    data class Start(val params: RunParams, val runId: Long) : RunCommand()
    object Stop : RunCommand()
}
```

- [ ] **Step 1.3: Commit**

```bash
git add app/src/main/java/com/autodial/model/RunState.kt app/src/main/java/com/autodial/model/RunCommand.kt
git commit -m "feat: RunState and RunCommand model classes"
```

---

### Task 2: RunStateMachine (with tests)

**Files:** `service/RunStateMachine.kt`, `test/.../RunStateMachineTest.kt`

The state machine is pure logic — no Android dependencies — so it can be unit-tested on the JVM.

- [ ] **Step 2.1: Write failing tests**

Create `app/src/test/java/com/autodial/service/RunStateMachineTest.kt`:

```kotlin
package com.autodial.service

import com.autodial.model.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RunStateMachineTest {

    private lateinit var sm: RunStateMachine

    private val params = RunParams(
        number = "67773777",
        targetPackage = "com.b3networks.bizphone",
        plannedCycles = 3,
        hangupSeconds = 10,
        spamModeSafetyCap = 9999,
        interDigitDelayMs = 400L
    )

    @Before fun setUp() { sm = RunStateMachine() }

    @Test fun initialStateIsIdle() = runTest {
        assertEquals(RunState.Idle, sm.state.value)
    }

    @Test fun startTransitionsToPreparing() = runTest {
        sm.onCommand(RunCommand.Start(params, 1L))
        assertTrue(sm.state.value is RunState.Preparing)
    }

    @Test fun targetForegroundedAfterLaunchTransitionsToOpenDialPad() = runTest {
        sm.onCommand(RunCommand.Start(params, 1L))
        sm.advanceToLaunching()
        sm.onEvent(RunEvent.TargetForegrounded(params.targetPackage))
        assertTrue("expected OpeningDialPad, got ${sm.state.value}",
            sm.state.value is RunState.OpeningDialPad)
    }

    @Test fun stopFromInCallHangsUpAndTransitionsToStopped() = runTest {
        sm.onCommand(RunCommand.Start(params, 1L))
        sm.forceState(RunState.InCall(params, 0, System.currentTimeMillis() + 10_000L))
        sm.onCommand(RunCommand.Stop)
        assertTrue("expected StoppedByUser or HangingUp, got ${sm.state.value}",
            sm.state.value is RunState.HangingUp || sm.state.value is RunState.StoppedByUser)
    }

    @Test fun stepFailedTransitionsToFailed() = runTest {
        sm.onCommand(RunCommand.Start(params, 1L))
        sm.forceState(RunState.PressingCall(params, 0))
        sm.onEvent(RunEvent.StepActionFailed("PRESS_CALL", "failed:timeout"))
        assertTrue("expected Failed, got ${sm.state.value}", sm.state.value is RunState.Failed)
    }

    @Test fun completedAfterAllCycles() = runTest {
        sm.onCommand(RunCommand.Start(params, 1L))
        sm.forceState(RunState.ReturningToDialPad(params, 2)) // last cycle (0-indexed)
        sm.onEvent(RunEvent.StepActionSucceeded("RETURN_TO_DIAL_PAD", "ok:node-primary"))
        assertTrue("expected Completed, got ${sm.state.value}", sm.state.value is RunState.Completed)
    }

    @Test fun spamModeContinuesBeyondPlannedCycles() = runTest {
        val spamParams = params.copy(plannedCycles = 0)
        sm.onCommand(RunCommand.Start(spamParams, 1L))
        sm.forceState(RunState.ReturningToDialPad(spamParams, 5))
        sm.onEvent(RunEvent.StepActionSucceeded("RETURN_TO_DIAL_PAD", "ok:node-primary"))
        assertTrue("should continue, not complete", sm.state.value is RunState.TypingDigits)
    }

    @Test fun spamModeStopsAtSafetyCap() = runTest {
        val spamParams = params.copy(plannedCycles = 0, spamModeSafetyCap = 5)
        sm.onCommand(RunCommand.Start(spamParams, 1L))
        sm.forceState(RunState.ReturningToDialPad(spamParams, 4)) // cycle 4 = 5th (0-indexed)
        sm.onEvent(RunEvent.StepActionSucceeded("RETURN_TO_DIAL_PAD", "ok:node-primary"))
        assertTrue("expected Completed at cap, got ${sm.state.value}",
            sm.state.value is RunState.Completed)
    }
}
```

- [ ] **Step 2.2: Run to verify failure**

```bash
./gradlew :app:test --tests "com.autodial.service.RunStateMachineTest"
```

Expected: `FAILED — unresolved reference: RunStateMachine`

- [ ] **Step 2.3: Create `service/RunStateMachine.kt`**

```kotlin
package com.autodial.service

import com.autodial.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class RunStateMachine {

    private val _state = MutableStateFlow<RunState>(RunState.Idle)
    val state: StateFlow<RunState> = _state.asStateFlow()

    private var runId: Long = 0L

    fun onCommand(cmd: RunCommand) {
        when (cmd) {
            is RunCommand.Start -> {
                if (_state.value !is RunState.Idle) return
                runId = cmd.runId
                _state.value = RunState.Preparing(cmd.params)
            }
            RunCommand.Stop -> handleStop()
        }
    }

    fun onEvent(event: RunEvent) {
        val s = _state.value
        when {
            event is RunEvent.TargetForegrounded && s is RunState.LaunchingTarget ->
                _state.value = RunState.OpeningDialPad(s.params, 0)

            event is RunEvent.TargetBackgrounded && s.isActive() ->
                _state.value = RunState.Failed(runId, "failed:target-app-closed")

            event is RunEvent.StepActionFailed ->
                _state.value = RunState.Failed(runId, event.reason)

            event is RunEvent.StepActionSucceeded -> handleStepSucceeded(event.stepId, s)

            else -> {}
        }
    }

    private fun handleStepSucceeded(stepId: String, s: RunState) {
        _state.value = when {
            stepId == "OPEN_DIAL_PAD" && s is RunState.OpeningDialPad ->
                RunState.TypingDigits(s.params, s.cycle, 0)

            stepId.startsWith("DIGIT_") && s is RunState.TypingDigits -> {
                val nextDigitIndex = s.digitIndex + 1
                if (nextDigitIndex < s.params.number.length)
                    RunState.TypingDigits(s.params, s.cycle, nextDigitIndex)
                else
                    RunState.PressingCall(s.params, s.cycle)
            }

            stepId == "PRESS_CALL" && s is RunState.PressingCall ->
                RunState.InCall(s.params, s.cycle,
                    System.currentTimeMillis() + s.params.hangupSeconds * 1000L)

            stepId in listOf("HANG_UP_CONNECTED", "HANG_UP_RINGING") && s is RunState.HangingUp ->
                RunState.ReturningToDialPad(s.params, s.cycle)

            stepId == "RETURN_TO_DIAL_PAD" && s is RunState.ReturningToDialPad -> {
                val nextCycle = s.cycle + 1
                val done = when {
                    s.params.plannedCycles == 0 -> nextCycle >= s.params.spamModeSafetyCap
                    else -> nextCycle >= s.params.plannedCycles
                }
                if (done) RunState.Completed(runId)
                else RunState.TypingDigits(s.params, nextCycle, 0)
            }

            else -> _state.value
        }
    }

    private fun handleStop() {
        val s = _state.value
        if (s.isTerminal() || s is RunState.Idle) return
        if (s is RunState.InCall || s is RunState.PressingCall) {
            _state.value = RunState.HangingUp(
                (s as? RunState.InCall)?.params ?: (s as RunState.PressingCall).params,
                (s as? RunState.InCall)?.cycle ?: (s as RunState.PressingCall).cycle
            )
        } else {
            _state.value = RunState.StoppedByUser(runId)
        }
    }

    fun advanceToLaunching() {
        val s = _state.value
        if (s is RunState.Preparing) _state.value = RunState.LaunchingTarget(s.params)
    }

    fun advanceToInCall(params: RunParams, cycle: Int) {
        _state.value = RunState.InCall(params, cycle,
            System.currentTimeMillis() + params.hangupSeconds * 1000L)
    }

    fun markHangupComplete(runId: Long, completed: Boolean) {
        _state.value = if (completed) RunState.Completed(runId) else RunState.StoppedByUser(runId)
    }

    fun forceState(state: RunState) { _state.value = state }  // test-only
}
```

- [ ] **Step 2.4: Run tests to verify they pass**

```bash
./gradlew :app:test --tests "com.autodial.service.RunStateMachineTest"
```

Expected: 8 tests pass.

- [ ] **Step 2.5: Commit**

```bash
git add app/src/main/java/com/autodial/service/RunStateMachine.kt app/src/test/java/com/autodial/service/RunStateMachineTest.kt
git commit -m "feat: RunStateMachine with full state transitions; tests passing"
```

---

### Task 3: OemCompatibilityHelper (with tests)

**Files:** `oem/OemCompatibilityHelper.kt`, `test/.../OemCompatibilityHelperTest.kt`

- [ ] **Step 3.1: Write failing tests**

Create `app/src/test/java/com/autodial/oem/OemCompatibilityHelperTest.kt`:

```kotlin
package com.autodial.oem

import android.content.Intent
import android.os.Build
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class OemCompatibilityHelperTest {

    @Test
    fun xiaomiManufacturerReturnsXiaomiSettings() {
        val helper = OemCompatibilityHelper.forManufacturer("Xiaomi")
        assertEquals(OemCompatibilityHelper.Oem.XIAOMI, helper.oem)
        val settings = helper.getRequiredSettings()
        assertTrue(settings.isNotEmpty())
        assertTrue(settings.any { it.id == "xiaomi_autostart" })
        assertTrue(settings.any { it.id == "xiaomi_battery" })
    }

    @Test
    fun redmiMapsToXiaomi() {
        assertEquals(OemCompatibilityHelper.Oem.XIAOMI,
            OemCompatibilityHelper.forManufacturer("Redmi").oem)
    }

    @Test
    fun oppoManufacturerReturnsOppoSettings() {
        val helper = OemCompatibilityHelper.forManufacturer("OPPO")
        assertEquals(OemCompatibilityHelper.Oem.OPPO, helper.oem)
        assertTrue(helper.getRequiredSettings().any { it.id == "oppo_auto_launch" })
    }

    @Test
    fun vivoManufacturerReturnsVivoSettings() {
        val helper = OemCompatibilityHelper.forManufacturer("vivo")
        assertEquals(OemCompatibilityHelper.Oem.VIVO, helper.oem)
        assertTrue(helper.getRequiredSettings().any { it.id == "vivo_autostart" })
    }

    @Test
    fun unknownManufacturerReturnsGeneric() {
        val helper = OemCompatibilityHelper.forManufacturer("Google")
        assertEquals(OemCompatibilityHelper.Oem.GENERIC, helper.oem)
        assertTrue(helper.getRequiredSettings().isEmpty())
    }

    @Test
    fun xiaomiAutostartHasDeepLink() {
        val helper = OemCompatibilityHelper.forManufacturer("Xiaomi")
        val autostart = helper.getRequiredSettings().first { it.id == "xiaomi_autostart" }
        assertNotNull(autostart.deepLinkIntent)
    }
}
```

Add Robolectric to `app/build.gradle.kts` test dependencies:

```kotlin
    testImplementation("org.robolectric:robolectric:4.13")
```

Also add to `android {}` block in `app/build.gradle.kts`:

```kotlin
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
```

- [ ] **Step 3.2: Run to verify failure**

```bash
./gradlew :app:test --tests "com.autodial.oem.OemCompatibilityHelperTest"
```

Expected: `FAILED — unresolved reference: OemCompatibilityHelper`

- [ ] **Step 3.3: Create `oem/OemCompatibilityHelper.kt`**

```kotlin
package com.autodial.oem

import android.content.ComponentName
import android.content.Intent

data class OemSetting(
    val id: String,
    val displayName: String,
    val description: String,
    val canVerify: Boolean,
    val verify: (() -> Boolean)?,
    val deepLinkIntent: Intent?
)

class OemCompatibilityHelper private constructor(
    val oem: Oem
) {
    enum class Oem { XIAOMI, OPPO, VIVO, SAMSUNG, GENERIC }

    companion object {
        fun forManufacturer(manufacturer: String): OemCompatibilityHelper {
            val oem = when (manufacturer.lowercase()) {
                "xiaomi", "redmi", "poco" -> Oem.XIAOMI
                "oppo", "realme" -> Oem.OPPO
                "vivo", "iqoo" -> Oem.VIVO
                "samsung" -> Oem.SAMSUNG
                else -> Oem.GENERIC
            }
            return OemCompatibilityHelper(oem)
        }
    }

    fun getRequiredSettings(): List<OemSetting> = when (oem) {
        Oem.XIAOMI -> xiaomiSettings()
        Oem.OPPO -> oppoSettings()
        Oem.VIVO -> vivoSettings()
        Oem.SAMSUNG -> samsungSettings()
        Oem.GENERIC -> emptyList()
    }

    private fun xiaomiSettings() = listOf(
        OemSetting(
            id = "xiaomi_autostart",
            displayName = "Autostart",
            description = "Security → Permissions → Autostart → enable AutoDial",
            canVerify = false, verify = null,
            deepLinkIntent = Intent().apply {
                component = ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        ),
        OemSetting(
            id = "xiaomi_battery",
            displayName = "Battery Saver — No Restrictions",
            description = "Settings → Battery → App battery saver → AutoDial → No restrictions",
            canVerify = false, verify = null,
            deepLinkIntent = Intent("miui.intent.action.POWER_HIDE_MODE_APP_LIST").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        ),
        OemSetting(
            id = "xiaomi_lock_screen",
            displayName = "Show on Lock Screen",
            description = "Settings → Apps → AutoDial → Other permissions → Show on Lock screen → allow",
            canVerify = false, verify = null, deepLinkIntent = null
        ),
        OemSetting(
            id = "xiaomi_lock_recents",
            displayName = "Lock in Recents",
            description = "Open Recents, pull down on the AutoDial card (or tap the lock icon on the card)",
            canVerify = false, verify = null, deepLinkIntent = null
        )
    )

    private fun oppoSettings() = listOf(
        OemSetting(
            id = "oppo_auto_launch",
            displayName = "Auto Launch",
            description = "Settings → Apps → Auto launch → enable AutoDial",
            canVerify = false, verify = null,
            deepLinkIntent = Intent().apply {
                action = "com.coloros.safecenter.permission.startup.StartupAppListActivity"
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        ),
        OemSetting(
            id = "oppo_battery",
            displayName = "App Power Management",
            description = "Settings → Battery → Power saving → App power management → AutoDial → enable all 3 toggles",
            canVerify = false, verify = null, deepLinkIntent = null
        ),
        OemSetting(
            id = "oppo_lock_recents",
            displayName = "Lock in Recents",
            description = "Open Recents, tap the lock icon on the AutoDial card",
            canVerify = false, verify = null, deepLinkIntent = null
        )
    )

    private fun vivoSettings() = listOf(
        OemSetting(
            id = "vivo_autostart",
            displayName = "Autostart",
            description = "Settings → More settings → Permission management → Autostart → AutoDial → enable",
            canVerify = false, verify = null, deepLinkIntent = null
        ),
        OemSetting(
            id = "vivo_battery",
            displayName = "Background Power Consumption",
            description = "Settings → Battery → Background power consumption management → AutoDial → allow",
            canVerify = false, verify = null, deepLinkIntent = null
        ),
        OemSetting(
            id = "vivo_lock_recents",
            displayName = "Lock in Recents",
            description = "Open Recents, tap the lock icon on the AutoDial card",
            canVerify = false, verify = null, deepLinkIntent = null
        )
    )

    private fun samsungSettings() = listOf(
        OemSetting(
            id = "samsung_battery",
            displayName = "Battery — Unrestricted",
            description = "Settings → Apps → AutoDial → Battery → Unrestricted",
            canVerify = false, verify = null,
            deepLinkIntent = Intent("android.settings.APPLICATION_DETAILS_SETTINGS").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        ),
        OemSetting(
            id = "samsung_never_sleep",
            displayName = "Never Sleeping Apps",
            description = "Settings → Device care → Battery → Background usage limits → Never sleeping apps → add AutoDial",
            canVerify = false, verify = null, deepLinkIntent = null
        )
    )
}
```

- [ ] **Step 3.4: Run tests to verify they pass**

```bash
./gradlew :app:test --tests "com.autodial.oem.OemCompatibilityHelperTest"
```

Expected: 6 tests pass.

- [ ] **Step 3.5: Commit**

```bash
git add app/src/main/java/com/autodial/oem/ app/src/test/java/com/autodial/oem/ app/build.gradle.kts
git commit -m "feat: OemCompatibilityHelper — per-OEM settings with deep-link intents"
```

---

### Task 4: RunForegroundService

**Files:** `service/RunForegroundService.kt`, update `AndroidManifest.xml`, `res/drawable/ic_notification.xml`

- [ ] **Step 4.1: Create notification icon `app/src/main/res/drawable/ic_notification.xml`**

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24"
    android:tint="#FFFFFF">
  <path
      android:fillColor="@android:color/white"
      android:pathData="M6.62,10.79c1.44,2.83 3.76,5.14 6.59,6.59l2.2,-2.2c0.27,-0.27 0.67,-0.36 1.02,-0.24 1.12,0.37 2.33,0.57 3.57,0.57 0.55,0 1,0.45 1,1V20c0,0.55 -0.45,1 -1,1 -9.39,0 -17,-7.61 -17,-17 0,-0.55 0.45,-1 1,-1h3.5c0.55,0 1,0.45 1,1 0,1.25 0.2,2.45 0.57,3.57 0.11,0.35 0.03,0.74 -0.25,1.02l-2.2,2.2z"/>
</vector>
```

- [ ] **Step 4.2: Add `RunForegroundService` to `AndroidManifest.xml`**

Inside `<application>`, after the accessibility service declaration:

```xml
        <service
            android:name=".service.RunForegroundService"
            android:exported="false"
            android:foregroundServiceType="dataSync" />
```

- [ ] **Step 4.3: Create `service/RunForegroundService.kt`**

```kotlin
package com.autodial.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.autodial.R
import com.autodial.accessibility.AutoDialAccessibilityService
import com.autodial.accessibility.StepOutcome
import com.autodial.data.db.entity.RunRecord
import com.autodial.data.db.entity.RunStatus
import com.autodial.data.db.entity.RunStepEvent
import com.autodial.data.repository.HistoryRepository
import com.autodial.data.repository.RecipeRepository
import com.autodial.model.*
import com.autodial.overlay.OverlayController
import com.autodial.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

@AndroidEntryPoint
class RunForegroundService : Service() {

    @Inject lateinit var recipeRepo: RecipeRepository
    @Inject lateinit var historyRepo: HistoryRepository

    companion object {
        const val CHANNEL_ID = "autodial_run"
        const val NOTIF_ID = 1
        const val ACTION_STOP = "com.autodial.ACTION_STOP"

        private val _publicState = MutableStateFlow<RunState>(RunState.Idle)
        val publicState: StateFlow<RunState> = _publicState.asStateFlow()

        fun start(context: Context, params: RunParams) {
            val intent = Intent(context, RunForegroundService::class.java).apply {
                putExtra("number", params.number)
                putExtra("targetPackage", params.targetPackage)
                putExtra("plannedCycles", params.plannedCycles)
                putExtra("hangupSeconds", params.hangupSeconds)
                putExtra("spamModeSafetyCap", params.spamModeSafetyCap)
                putExtra("interDigitDelayMs", params.interDigitDelayMs)
            }
            context.startForegroundService(intent)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val stateMachine = RunStateMachine()
    private var wakeLock: PowerManager.WakeLock? = null
    private var overlayController: OverlayController? = null
    private var runId: Long = 0L
    private var currentParams: RunParams? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        overlayController = OverlayController(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopRun(); return START_NOT_STICKY }

        val params = intent?.toRunParams() ?: return START_NOT_STICKY
        currentParams = params
        startForeground(NOTIF_ID, buildNotification(params.number, "Starting…"))
        acquireWakeLock()
        launchRun(params)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        overlayController?.dismiss()
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Run lifecycle ──────────────────────────────────────────────────────

    private fun launchRun(params: RunParams) {
        scope.launch {
            val now = System.currentTimeMillis()
            runId = historyRepo.startRun(RunRecord(
                startedAt = now, endedAt = now,
                number = params.number, targetPackage = params.targetPackage,
                plannedCycles = params.plannedCycles, completedCycles = 0,
                hangupSeconds = params.hangupSeconds,
                status = RunStatus.DONE, failureReason = null
            ))

            val accessService = AutoDialAccessibilityService.instance
            if (accessService == null) {
                finishRun(RunStatus.FAILED, "failed:service-not-bound")
                return@launch
            }

            accessService.beginRun(params)
            stateMachine.onCommand(RunCommand.Start(params, runId))
            _publicState.value = stateMachine.state.value

            // Collect accessibility events → state machine
            launch {
                accessService.runEvents.collect { event ->
                    stateMachine.onEvent(event)
                    _publicState.value = stateMachine.state.value
                }
            }

            // Drive the state machine
            stateMachine.state.collect { state ->
                _publicState.value = state
                overlayController?.updateState(state)
                updateNotification(state)
                driveState(state, params, accessService)
            }
        }
    }

    private suspend fun driveState(
        state: RunState,
        params: RunParams,
        accessService: AutoDialAccessibilityService
    ) {
        when (state) {
            is RunState.Preparing -> {
                // Launch target app — we are foreground right now, so no BAL restriction
                val launchIntent = packageManager.getLaunchIntentForPackage(params.targetPackage)
                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                if (launchIntent == null) {
                    stateMachine.onEvent(RunEvent.StepActionFailed("LAUNCH", "failed:target-not-installed"))
                    return
                }
                startActivity(launchIntent)
                stateMachine.advanceToLaunching()
                withTimeout(5000L) {
                    stateMachine.state.first { it is RunState.OpeningDialPad || it.isTerminal() }
                }
            }

            is RunState.OpeningDialPad -> executeStep("OPEN_DIAL_PAD", state.params, accessService)

            is RunState.TypingDigits -> {
                val digit = state.params.number[state.digitIndex].toString()
                executeStep("DIGIT_$digit", state.params, accessService)
                if (state.digitIndex < state.params.number.length - 1) {
                    delay(state.params.interDigitDelayMs)
                }
            }

            is RunState.PressingCall -> executeStep("PRESS_CALL", state.params, accessService)

            is RunState.InCall -> {
                val remaining = state.hangupAt - System.currentTimeMillis()
                if (remaining > 0) delay(remaining)
                val steps = recipeRepo.getSteps(state.params.targetPackage)
                val root = accessService.rootInActiveWindow
                val hangupStep = if (root != null && steps.any { it.stepId == "HANG_UP_CONNECTED" }) {
                    "HANG_UP_CONNECTED"
                } else {
                    "HANG_UP_RINGING"
                }
                stateMachine.onEvent(RunEvent.StepActionSucceeded("PRESS_CALL", "ok:timer-elapsed"))
                executeStep(hangupStep, state.params, accessService)
            }

            is RunState.HangingUp -> { /* driven by IN_CALL above */ }

            is RunState.ReturningToDialPad -> {
                delay(800L) // inter-cycle settle
                executeStep("RETURN_TO_DIAL_PAD", state.params, accessService)
            }

            is RunState.Completed -> finishRun(RunStatus.DONE, null)
            is RunState.StoppedByUser -> finishRun(RunStatus.STOPPED, null)
            is RunState.Failed -> finishRun(RunStatus.FAILED, state.reason)
            else -> {}
        }
    }

    private suspend fun executeStep(
        stepId: String,
        params: RunParams,
        accessService: AutoDialAccessibilityService
    ) {
        val steps = recipeRepo.getSteps(params.targetPackage)
        val step = steps.firstOrNull { it.stepId == stepId }
        if (step == null) {
            stateMachine.onEvent(RunEvent.StepActionFailed(stepId, "failed:step-not-recorded"))
            return
        }
        val outcome = accessService.executeStep(step)
        val outStr = when (outcome) {
            is StepOutcome.Ok -> outcome.outcome
            is StepOutcome.Failed -> outcome.reason
        }
        historyRepo.logStepEvent(RunStepEvent(
            runId = runId, cycleIndex = currentCycle(), stepId = stepId,
            at = System.currentTimeMillis(), outcome = outStr, detail = null
        ))
        when (outcome) {
            is StepOutcome.Ok -> stateMachine.onEvent(RunEvent.StepActionSucceeded(stepId, outStr))
            is StepOutcome.Failed -> stateMachine.onEvent(RunEvent.StepActionFailed(stepId, outStr))
        }
    }

    private fun currentCycle(): Int {
        return when (val s = stateMachine.state.value) {
            is RunState.TypingDigits -> s.cycle
            is RunState.PressingCall -> s.cycle
            is RunState.InCall -> s.cycle
            is RunState.HangingUp -> s.cycle
            is RunState.ReturningToDialPad -> s.cycle
            else -> 0
        }
    }

    private fun stopRun() {
        stateMachine.onCommand(RunCommand.Stop)
    }

    private fun finishRun(status: RunStatus, reason: String?) {
        scope.launch {
            AutoDialAccessibilityService.instance?.endRun()
            overlayController?.dismiss()
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            _publicState.value = RunState.Idle
            // Bring AutoDial back to foreground
            val intent = Intent(this@RunForegroundService, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(intent)
        }
    }

    // ── Wake lock ──────────────────────────────────────────────────────────

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
            PowerManager.ON_AFTER_RELEASE or
            PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "AutoDial:run"
        ).also {
            it.setReferenceCounted(false)
            it.acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    // ── Notification ───────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "AutoDial Run", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(number: String, statusText: String): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, RunForegroundService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AutoDial: $number")
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openIntent)
            .addAction(0, "STOP", stopIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(state: RunState) {
        val text = when (state) {
            is RunState.TypingDigits -> "Cycle ${state.cycle + 1} — dialing"
            is RunState.InCall -> "Cycle ${state.cycle + 1} — in call"
            is RunState.HangingUp -> "Hanging up"
            is RunState.Completed -> "Done"
            else -> state::class.simpleName ?: ""
        }
        val params = currentParams ?: return
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(params.number, text))
    }

    private fun Intent.toRunParams(): RunParams? {
        val number = getStringExtra("number") ?: return null
        val pkg = getStringExtra("targetPackage") ?: return null
        return RunParams(
            number = number,
            targetPackage = pkg,
            plannedCycles = getIntExtra("plannedCycles", 10),
            hangupSeconds = getIntExtra("hangupSeconds", 25),
            spamModeSafetyCap = getIntExtra("spamModeSafetyCap", 9999),
            interDigitDelayMs = getLongExtra("interDigitDelayMs", 400L)
        )
    }
}
```

- [ ] **Step 4.4: Build to confirm no compile errors**

```bash
./gradlew :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4.5: Commit**

```bash
git add app/src/main/java/com/autodial/service/ app/src/main/AndroidManifest.xml app/src/main/res/drawable/ic_notification.xml
git commit -m "feat: RunForegroundService — state machine driver, wake lock, notification with STOP action"
```

---

### Task 5: OverlayController

**Files:** `overlay/OverlayController.kt`

- [ ] **Step 5.1: Create `overlay/OverlayController.kt`**

```kotlin
package com.autodial.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.*
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import com.autodial.model.RunState
import com.autodial.service.RunForegroundService

class OverlayController(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: ComposeView? = null
    private val lifecycleOwner = SimpleLifecycleOwner()

    private var overlayX = 0
    private var overlayY = 200

    private val _state = mutableStateOf<RunState>(RunState.Idle)

    fun updateState(state: RunState) {
        _state.value = state
        val isActive = state.isActive()
        if (isActive && overlayView == null) show()
        else if (!isActive) dismiss()
    }

    private fun show() {
        lifecycleOwner.start()
        val view = ComposeView(context).apply {
            setViewCompositionStrategy(
                androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnDetachedFromWindow
            )
            setContent {
                OverlayBubble(
                    state = _state.value,
                    onStop = { RunForegroundService.publicState.value.let {
                        context.startService(
                            android.content.Intent(context, RunForegroundService::class.java)
                                .apply { action = RunForegroundService.ACTION_STOP }
                        )
                    }},
                    onDrag = { dx, dy ->
                        overlayX = (overlayX + dx.toInt())
                        overlayY = (overlayY + dy.toInt())
                        updateLayoutPosition()
                    }
                )
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = overlayX; y = overlayY
        }

        windowManager.addView(view, params)
        overlayView = view
    }

    fun dismiss() {
        overlayView?.let { windowManager.removeView(it) }
        overlayView = null
        lifecycleOwner.stop()
    }

    private fun updateLayoutPosition() {
        val view = overlayView ?: return
        val params = view.layoutParams as WindowManager.LayoutParams
        params.x = overlayX; params.y = overlayY
        windowManager.updateViewLayout(view, params)
    }

    private fun RunState.isActive(): Boolean =
        this !is RunState.Idle && this !is RunState.Completed &&
        this !is RunState.StoppedByUser && this !is RunState.Failed

    private inner class SimpleLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val savedStateController = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

        fun start() {
            savedStateController.performAttach()
            savedStateController.performRestore(null)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }

        fun stop() {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        }
    }
}

@Composable
private fun OverlayBubble(
    state: RunState,
    onStop: () -> Unit,
    onDrag: (Float, Float) -> Unit
) {
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }

    val (line1, line2) = when (state) {
        is RunState.TypingDigits -> "Cycle ${state.cycle + 1}" to "Dialing ${state.params.number}"
        is RunState.InCall -> {
            val remaining = ((state.hangupAt - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L)
            "Cycle ${state.cycle + 1} · ${remaining}s" to state.params.number
        }
        is RunState.HangingUp -> "Hanging up" to ""
        is RunState.ReturningToDialPad -> "Returning to dial pad" to ""
        else -> "" to ""
    }

    Box(
        Modifier
            .background(Color(0xCC_00_00_00), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .pointerInput(Unit) {
                detectDragGestures { _, dragAmount ->
                    onDrag(dragAmount.x, dragAmount.y)
                }
            }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.widthIn(min = 100.dp)) {
                if (line1.isNotEmpty()) Text(line1, color = Color.White, fontSize = 13.sp)
                if (line2.isNotEmpty()) Text(line2, color = Color(0xFFAAAAAA), fontSize = 11.sp)
            }
            Spacer(Modifier.width(12.dp))
            Button(
                onClick = onStop,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935)),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            ) {
                Text("STOP", color = Color.White, fontSize = 12.sp)
            }
        }
    }
}
```

Also add to `AndroidManifest.xml` inside `<application>`:

```xml
        <!-- savedstate library requires this for OverlayController's LifecycleOwner -->
```

Add to `app/build.gradle.kts` dependencies:

```kotlin
    implementation("androidx.savedstate:savedstate-ktx:1.2.1")
```

- [ ] **Step 5.2: Build to confirm no compile errors**

```bash
./gradlew :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5.3: Commit**

```bash
git add app/src/main/java/com/autodial/overlay/ app/build.gradle.kts
git commit -m "feat: OverlayController — draggable floating bubble with STOP chip via Compose + WindowManager"
```

---

### Task 6: 30-second Self-check in AccessibilityService

The PRD requires that during a run, every 30 seconds the service verifies it's still alive and the overlay is still valid.

- [ ] **Step 6.1: Add `startSelfCheckLoop()` to `AutoDialAccessibilityService.kt`**

Add these two methods to `AutoDialAccessibilityService`:

```kotlin
    fun startSelfCheckLoop() {
        scope.launch {
            while (true) {
                delay(30_000L)
                if (!selfCheck()) {
                    _runEvents.emit(RunEvent.StepActionFailed("SELF_CHECK", "failed:accessibility-service-revoked"))
                    break
                }
            }
        }
    }

    fun stopSelfCheckLoop() {
        // The loop exits naturally when the scope is cancelled or selfCheck() fails
    }
```

Call `accessService.startSelfCheckLoop()` inside `RunForegroundService.launchRun()` immediately after `accessService.beginRun(params)`.

- [ ] **Step 6.2: Build**

```bash
./gradlew :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6.3: Commit**

```bash
git add app/src/main/java/com/autodial/accessibility/AutoDialAccessibilityService.kt app/src/main/java/com/autodial/service/RunForegroundService.kt
git commit -m "feat: 30s self-check loop in accessibility service during runs"
```

---

**Plan 3 complete.** Run orchestration is fully wired: state machine tested, OEM helper tested, FGS drives runs, overlay shows live progress. Proceed to Plan 4 (UI).

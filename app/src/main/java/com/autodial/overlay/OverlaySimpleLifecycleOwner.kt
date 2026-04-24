package com.autodial.overlay

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner

/**
 * Minimal [LifecycleOwner] + [SavedStateRegistryOwner] wired up for a
 * short-lived [androidx.compose.ui.platform.ComposeView] hosted inside a
 * window overlay via `WindowManager.addView`.
 *
 * Extracted from the previously duplicated inner classes in
 * [OverlayController] and [WizardOverlayController]; both consumers now
 * share this implementation so a fix in one place applies to both.
 *
 * Usage pattern:
 * ```
 * val owner = OverlaySimpleLifecycleOwner().also { it.start() }
 * view.setViewTreeLifecycleOwner(owner)
 * view.setViewTreeSavedStateRegistryOwner(owner)
 * // ... use view ...
 * owner.stop()  // before removing the view from the window manager
 * ```
 */
class OverlaySimpleLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner {
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
        // Guard against stop() being called before start() (INITIALIZED state).
        if (registry.currentState == Lifecycle.State.INITIALIZED) return
        registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }
}

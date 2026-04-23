package com.autodial.ui.activerun

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import com.autodial.model.RunState
import com.autodial.service.RunForegroundService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class ActiveRunViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {
    val runState: StateFlow<RunState> = RunForegroundService.publicState

    fun stop() {
        context.startService(
            Intent(context, RunForegroundService::class.java)
                .apply { action = RunForegroundService.ACTION_STOP }
        )
    }
}

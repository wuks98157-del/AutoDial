package com.autodial.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.autodial.data.db.entity.RunRecord
import com.autodial.data.db.entity.RunStepEvent
import com.autodial.data.repository.HistoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HistoryViewModel @Inject constructor(private val repo: HistoryRepository) : ViewModel() {

    val runs: StateFlow<List<RunRecord>> = repo.observeRuns()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedRunEvents = MutableStateFlow<List<RunStepEvent>>(emptyList())
    val selectedRunEvents: StateFlow<List<RunStepEvent>> = _selectedRunEvents.asStateFlow()

    fun loadStepEvents(runId: Long) {
        viewModelScope.launch {
            _selectedRunEvents.value = repo.getStepEvents(runId)
        }
    }

    fun clearAll() { viewModelScope.launch { repo.clearAll() } }

    fun clearOlderThan30Days() {
        viewModelScope.launch {
            repo.clearOlderThan(System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000)
        }
    }
}

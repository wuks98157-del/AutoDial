package com.autodial.ui.history

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.autodial.data.db.entity.RunRecord
import com.autodial.data.db.entity.RunStepEvent
import com.autodial.data.repository.HistoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    /**
     * Writes the current run list to a CSV file under the app's external
     * files dir. Calls [onResult] with the file path on success, or an
     * error message prefixed with "!" on failure. Runs off-main.
     */
    fun exportHistory(context: Context, onResult: (String) -> Unit) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val file = HistoryExporter.exportToFile(context, runs.value)
                    file.absolutePath
                } catch (t: Throwable) {
                    "!" + (t.message ?: "export failed")
                }
            }
            onResult(result)
        }
    }
}

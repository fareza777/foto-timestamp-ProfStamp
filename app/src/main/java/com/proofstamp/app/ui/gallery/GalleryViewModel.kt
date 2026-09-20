package com.proofstamp.app.ui.gallery

import android.app.Activity
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.proofstamp.app.ads.AdsManager
import com.proofstamp.app.data.db.PhotoEntity
import com.proofstamp.app.di.AppContainer
import com.proofstamp.app.share.ShareMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Calendar

enum class DateFilter { ALL, TODAY, WEEK }

data class GalleryUiState(
    val photos: List<PhotoEntity> = emptyList(),
    val projects: List<String> = emptyList(),
    val dateFilter: DateFilter = DateFilter.ALL,
    val projectFilter: String? = null,
    val selected: Set<String> = emptySet(),
    val busy: Boolean = false,
    val stripGps: Boolean = true,
) {
    val selecting: Boolean get() = selected.isNotEmpty()
    val grouped: List<Pair<String, List<PhotoEntity>>>
        get() = photos.groupBy { dayKey(it.capturedAt) }.map { it.key to it.value }

    private fun dayKey(ts: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = ts }
        return "%04d-%02d-%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }
}

/** One-off share/export events — the UI fires the intent and optionally an interstitial. */
data class ShareEvent(val intent: Intent, val showInterstitial: Boolean)

class GalleryViewModel(private val container: AppContainer) : ViewModel() {
    private val filters = MutableStateFlow(DateFilter.ALL to null as String?)
    private val selected = MutableStateFlow<Set<String>>(emptySet())
    private val busy = MutableStateFlow(false)
    private val _events = MutableStateFlow<ShareEvent?>(null)
    val events: StateFlow<ShareEvent?> = _events

    val state: StateFlow<GalleryUiState> = combine(
        container.photoRepository.observeAll(),
        container.photoRepository.observeProjects(),
        filters,
        selected,
        combine(busy, container.settings.settings) { b, s -> b to s },
    ) { photos, projects, (dateFilter, project), sel, (isBusy, settings) ->
        val since = when (dateFilter) {
            DateFilter.ALL -> 0L
            DateFilter.TODAY -> startOfDay()
            DateFilter.WEEK -> startOfDay() - 6 * 86_400_000L
        }
        GalleryUiState(
            photos = photos.filter { it.capturedAt >= since && (project == null || it.project == project) },
            projects = projects,
            dateFilter = dateFilter,
            projectFilter = project,
            selected = sel.filter { id -> photos.any { it.id == id } }.toSet(),
            busy = isBusy,
            stripGps = settings.stripGpsOnShare,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GalleryUiState())

    private fun startOfDay(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun setDateFilter(f: DateFilter) = filters.update { it.copy(first = f) }
    fun setProjectFilter(p: String?) = filters.update { it.copy(second = p) }

    fun toggleSelect(id: String) = selected.update { if (id in it) it - id else it + id }
    fun clearSelection() = selected.update { emptySet() }
    fun selectAll() = selected.update { state.value.photos.map { it.id }.toSet() }

    fun share(mode: ShareMode) = exportSelected { items -> container.exportManager.shareIntent(items, mode) }

    fun exportZip(mode: ShareMode) = exportSelected { items -> container.exportManager.exportZip(items, null, mode) }

    fun deleteSelected() = viewModelScope.launch {
        val items = container.photoRepository.getByIds(selected.value.toList())
        items.forEach { container.photoRepository.delete(it) }
        items.mapNotNull { it.sessionId }.distinct().forEach { container.sessionRepository.refreshCount(it) }
        clearSelection()
    }

    private fun exportSelected(block: suspend (List<PhotoEntity>) -> Intent) = viewModelScope.launch {
        val ids = selected.value.toList()
        if (ids.isEmpty()) return@launch
        busy.value = true
        try {
            val items = container.photoRepository.getByIds(ids).sortedBy { it.capturedAt }
            val intent = block(items)
            val s = container.settings.current()
            val show = AdsManager.shouldShowInterstitial(s.exportCount, s.lastInterstitialAt)
            if (show) container.settings.markInterstitialShown(System.currentTimeMillis())
            _events.value = ShareEvent(intent, show)
            clearSelection()
        } finally {
            busy.value = false
        }
    }

    fun consumeEvent() { _events.value = null }

    fun maybeShowInterstitial(activity: Activity) {
        AdsManager.showInterstitial(activity)
    }
}

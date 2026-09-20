package com.proofstamp.app.ui.sessions

import android.app.Activity
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.ViewTimeline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.proofstamp.app.R
import com.proofstamp.app.ads.AdsManager
import com.proofstamp.app.data.db.PhotoEntity
import com.proofstamp.app.data.db.SessionEntity
import com.proofstamp.app.di.AppContainer
import com.proofstamp.app.share.ShareMode
import com.proofstamp.app.ui.components.EmptyState
import com.proofstamp.app.ui.components.KeyValueRow
import com.proofstamp.app.ui.components.PsCard
import com.proofstamp.app.ui.components.StatusPill
import com.proofstamp.app.ui.gallery.PhotoTile
import com.proofstamp.app.ui.gallery.ShareModeMenu
import com.proofstamp.app.ui.nav.containerViewModel
import com.proofstamp.app.ui.theme.Mono
import com.proofstamp.app.ui.theme.PsColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SessionsViewModel(container: AppContainer) : ViewModel() {
    val sessions: StateFlow<List<SessionEntity>> = container.sessionRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

class SessionDetailViewModel(private val container: AppContainer, private val sessionId: String) : ViewModel() {
    val session: StateFlow<SessionEntity?> = container.sessionRepository.observeById(sessionId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val photos: StateFlow<List<PhotoEntity>> = container.photoRepository.observeBySession(sessionId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val stripGps: StateFlow<Boolean> = container.settings.settings.map { it.stripGpsOnShare }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy
    private val _export = MutableStateFlow<Pair<Intent, Boolean>?>(null)
    val export: StateFlow<Pair<Intent, Boolean>?> = _export

    fun exportZip(mode: ShareMode) = viewModelScope.launch {
        val items = photos.value
        if (items.isEmpty()) return@launch
        _busy.value = true
        try {
            val intent = container.exportManager.exportZip(items, session.value, mode)
            val count = container.settings.incrementExportCount()
            val s = container.settings.current()
            val show = AdsManager.shouldShowInterstitial(count, s.lastInterstitialAt)
            if (show) container.settings.markInterstitialShown(System.currentTimeMillis())
            _export.value = intent to show
        } finally {
            _busy.value = false
        }
    }

    fun consumeExport() { _export.value = null }

    fun end() = viewModelScope.launch { container.sessionRepository.end(sessionId) }

    fun delete(onDone: () -> Unit) = viewModelScope.launch {
        session.value?.let { container.sessionRepository.delete(it) }
        onDone()
    }
}

private fun dateFmt() = SimpleDateFormat("dd MMM yyyy · HH:mm", Locale.getDefault())

@Composable
fun SessionsScreen(container: AppContainer, onOpenSession: (String) -> Unit) {
    val vm = containerViewModel { SessionsViewModel(container) }
    val sessions by vm.sessions.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().background(PsColors.Bg).statusBarsPadding()) {
        Text(
            stringResource(R.string.nav_sessions),
            style = MaterialTheme.typography.headlineMedium,
            color = PsColors.Text,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
        )
        if (sessions.isEmpty()) {
            EmptyState(Icons.Outlined.ViewTimeline, stringResource(R.string.sessions_empty), stringResource(R.string.sessions_empty_body))
        } else {
            LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(sessions, key = { it.id }) { s -> SessionCard(s) { onOpenSession(s.id) } }
            }
        }
    }
}

@Composable
private fun SessionCard(s: SessionEntity, onClick: () -> Unit) {
    val active = s.endedAt == null
    PsCard(onClick = onClick, accent = if (active) PsColors.Accent else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(s.name, style = MaterialTheme.typography.titleMedium, color = PsColors.Text)
                Spacer(Modifier.height(2.dp))
                Text(s.id, style = MaterialTheme.typography.labelMedium.copy(fontFamily = Mono), color = PsColors.TextDim)
            }
            StatusPill(stringResource(if (active) R.string.active else R.string.ended), if (active) PsColors.Accent else PsColors.TextDim)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.session_photos, s.photoCount), style = MaterialTheme.typography.bodySmall, color = PsColors.TextDim)
            Text(remember(s.startedAt) { dateFmt().format(Date(s.startedAt)) }, style = MaterialTheme.typography.bodySmall, color = PsColors.TextDim)
            if (s.project.isNotBlank()) Text(s.project, style = MaterialTheme.typography.bodySmall, color = PsColors.TextDim)
        }
    }
}

@Composable
fun SessionDetailScreen(container: AppContainer, sessionId: String, onBack: () -> Unit, onOpenPhoto: (String) -> Unit) {
    val vm = containerViewModel(key = sessionId) { SessionDetailViewModel(container, sessionId) }
    val session by vm.session.collectAsStateWithLifecycle()
    val photos by vm.photos.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val export by vm.export.collectAsStateWithLifecycle()
    val stripGps by vm.stripGps.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var zipMenu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(export) {
        val (intent, ad) = export ?: return@LaunchedEffect
        context.startActivity(Intent.createChooser(intent, null))
        if (ad) (context as? Activity)?.let { AdsManager.showInterstitial(it) }
        vm.consumeExport()
    }

    val s = session
    Column(Modifier.fillMaxSize().background(PsColors.Bg)) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, null, tint = PsColors.Text) }
            Text(s?.name ?: "", style = MaterialTheme.typography.titleMedium, color = PsColors.Text, modifier = Modifier.weight(1f))
            IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.Delete, stringResource(R.string.delete), tint = PsColors.Danger) }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = PsColors.Accent, trackColor = PsColors.SurfaceHigh)

        if (s == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = PsColors.Accent) }
            return
        }

        val active = s.endedAt == null
        Column(Modifier.padding(horizontal = 16.dp)) {
            PsCard(accent = if (active) PsColors.Accent else null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.id, style = MaterialTheme.typography.titleMedium.copy(fontFamily = Mono), color = PsColors.Text, modifier = Modifier.weight(1f))
                    StatusPill(stringResource(if (active) R.string.active else R.string.ended), if (active) PsColors.Accent else PsColors.TextDim)
                }
                Spacer(Modifier.height(8.dp))
                val fmt = remember { dateFmt() }
                KeyValueRow("Started", fmt.format(Date(s.startedAt)))
                s.endedAt?.let { KeyValueRow("Ended", fmt.format(Date(it))) }
                if (s.project.isNotBlank()) KeyValueRow(stringResource(R.string.project), s.project)
                if (s.operator.isNotBlank()) KeyValueRow(stringResource(R.string.operator), s.operator)
                KeyValueRow("Photos", photos.size.toString())
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box {
                        Button(onClick = { zipMenu = true }, enabled = photos.isNotEmpty() && !busy) {
                            Icon(Icons.Outlined.Archive, null)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.export_report))
                        }
                        ShareModeMenu(expanded = zipMenu, stripGps = stripGps, onDismiss = { zipMenu = false }, zip = true) { vm.exportZip(it) }
                    }
                    if (active) {
                        OutlinedButton(onClick = vm::end) {
                            Icon(Icons.Outlined.StopCircle, null)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.end_session))
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.weight(1f),
        ) {
            items(photos, key = { it.id }) { p ->
                PhotoTile(p, selected = false, selecting = false, modifier = Modifier.clickable { onOpenPhoto(p.id) })
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete)) },
            text = { Text("Delete this session? Photos stay in the gallery and keep their Session ID.") },
            confirmButton = { Button(onClick = { confirmDelete = false; vm.delete(onBack) }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

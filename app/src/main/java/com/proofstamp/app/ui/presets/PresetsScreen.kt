package com.proofstamp.app.ui.presets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.proofstamp.app.R
import com.proofstamp.app.data.db.PresetEntity
import com.proofstamp.app.di.AppContainer
import com.proofstamp.app.ui.components.PsCard
import com.proofstamp.app.ui.nav.containerViewModel
import com.proofstamp.app.ui.theme.PsColors
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PresetsViewModel(private val container: AppContainer) : ViewModel() {
    val presets: StateFlow<List<PresetEntity>> = container.presetRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val activeId: StateFlow<Long?> = container.settings.settings.map { it.activePresetId }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun save(preset: PresetEntity) = viewModelScope.launch {
        val order = if (preset.id == 0L) (presets.value.maxOfOrNull { it.sortOrder } ?: 0) + 1 else preset.sortOrder
        container.presetRepository.upsert(preset.copy(sortOrder = order))
    }

    fun delete(preset: PresetEntity) = viewModelScope.launch {
        container.presetRepository.delete(preset)
        if (activeId.value == preset.id) container.settings.setActivePreset(null)
    }

    fun activate(preset: PresetEntity) = viewModelScope.launch {
        container.settings.setActivePreset(if (activeId.value == preset.id) null else preset.id)
    }
}

@Composable
fun PresetsScreen(container: AppContainer, onBack: () -> Unit) {
    val vm = containerViewModel { PresetsViewModel(container) }
    val presets by vm.presets.collectAsStateWithLifecycle()
    val activeId by vm.activeId.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<PresetEntity?>(null) }

    Scaffold(
        containerColor = PsColors.Bg,
        floatingActionButton = {
            FloatingActionButton(
                onClick = { editing = PresetEntity(name = "", project = "", operator = "", locationLabel = "", defaultNote = "", sortOrder = 0) },
                containerColor = PsColors.Accent,
                contentColor = PsColors.OnAccent,
            ) { Icon(Icons.Outlined.Add, stringResource(R.string.preset_new)) }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, null, tint = PsColors.Text) }
                Text(stringResource(R.string.presets), style = MaterialTheme.typography.titleLarge, color = PsColors.Text)
            }
            Text(
                "Pick a preset on the camera and the watermark fields fill themselves. Tap to make one active by default.",
                style = MaterialTheme.typography.bodySmall,
                color = PsColors.TextDim,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(12.dp))
            LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(presets, key = { it.id }) { p ->
                    val active = p.id == activeId
                    PsCard(onClick = { vm.activate(p) }, accent = if (active) PsColors.Accent else null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(p.name, style = MaterialTheme.typography.titleMedium, color = PsColors.Text)
                                val sub = listOf(p.project, p.operator, p.locationLabel).filter { it.isNotBlank() }.joinToString(" · ")
                                if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = PsColors.TextDim)
                                if (p.defaultNote.isNotBlank()) Text(p.defaultNote, style = MaterialTheme.typography.bodySmall, color = PsColors.TextFaint)
                            }
                            if (active) Icon(Icons.Outlined.CheckCircle, null, tint = PsColors.Accent)
                            IconButton(onClick = { editing = p }) { Icon(Icons.Outlined.Edit, null, tint = PsColors.TextDim) }
                            IconButton(onClick = { vm.delete(p) }) { Icon(Icons.Outlined.Delete, null, tint = PsColors.Danger) }
                        }
                    }
                }
            }
        }
    }

    editing?.let { initial ->
        PresetDialog(initial, onDismiss = { editing = null }) { vm.save(it); editing = null }
    }
}

@Composable
private fun PresetDialog(initial: PresetEntity, onDismiss: () -> Unit, onSave: (PresetEntity) -> Unit) {
    var name by remember { mutableStateOf(initial.name) }
    var project by remember { mutableStateOf(initial.project) }
    var operator by remember { mutableStateOf(initial.operator) }
    var location by remember { mutableStateOf(initial.locationLabel) }
    var note by remember { mutableStateOf(initial.defaultNote) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial.id == 0L) R.string.preset_new else R.string.presets)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.preset_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(project, { project = it }, label = { Text(stringResource(R.string.preset_project)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(operator, { operator = it }, label = { Text(stringResource(R.string.preset_operator)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(location, { location = it }, label = { Text(stringResource(R.string.preset_location)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(note, { note = it }, label = { Text(stringResource(R.string.preset_note)) }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Button(
                enabled = name.isNotBlank(),
                onClick = {
                    onSave(initial.copy(name = name.trim(), project = project.trim(), operator = operator.trim(), locationLabel = location.trim(), defaultNote = note.trim()))
                },
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

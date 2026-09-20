package com.proofstamp.app.ui.gallery

import android.app.Activity
import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.proofstamp.app.R
import com.proofstamp.app.ads.AdBanner
import com.proofstamp.app.data.crypto.Ids
import com.proofstamp.app.data.db.PhotoEntity
import com.proofstamp.app.di.AppContainer
import com.proofstamp.app.share.ShareMode
import com.proofstamp.app.ui.components.EmptyState
import com.proofstamp.app.ui.nav.containerViewModel
import com.proofstamp.app.ui.theme.Mono
import com.proofstamp.app.ui.theme.PsColors
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun GalleryScreen(container: AppContainer, onOpenPhoto: (String) -> Unit) {
    val vm = containerViewModel { GalleryViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()
    val event by vm.events.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(event) {
        val e = event ?: return@LaunchedEffect
        context.startActivity(Intent.createChooser(e.intent, null))
        if (e.showInterstitial) (context as? Activity)?.let(vm::maybeShowInterstitial)
        vm.consumeEvent()
    }

    Column(Modifier.fillMaxSize().background(PsColors.Bg)) {
        if (state.selecting) {
            SelectionBar(state, vm, onDelete = { confirmDelete = true })
        } else {
            Header(state, vm)
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = PsColors.Accent, trackColor = PsColors.SurfaceHigh)

        Box(Modifier.weight(1f)) {
            if (state.photos.isEmpty()) {
                EmptyState(Icons.Outlined.Collections, stringResource(R.string.gallery_empty), stringResource(R.string.gallery_empty_body))
            } else {
                PhotoGrid(state, onOpen = onOpenPhoto, onToggle = vm::toggleSelect)
            }
        }
        AdBanner()
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete)) },
            text = { Text(stringResource(R.string.delete_photo_confirm)) },
            confirmButton = { Button(onClick = { vm.deleteSelected(); confirmDelete = false }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun Header(state: GalleryUiState, vm: GalleryViewModel) {
    Column(Modifier.statusBarsPadding().padding(top = 8.dp)) {
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.nav_gallery), style = MaterialTheme.typography.headlineMedium, color = PsColors.Text)
                Text("${state.photos.size} sealed captures", style = MaterialTheme.typography.bodySmall, color = PsColors.TextDim)
            }
            Icon(Icons.Outlined.Shield, null, tint = PsColors.Accent)
        }
        Spacer(Modifier.height(12.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Chip(stringResource(R.string.filter_all), state.dateFilter == DateFilter.ALL) { vm.setDateFilter(DateFilter.ALL) } }
            item { Chip(stringResource(R.string.filter_today), state.dateFilter == DateFilter.TODAY) { vm.setDateFilter(DateFilter.TODAY) } }
            item { Chip(stringResource(R.string.filter_week), state.dateFilter == DateFilter.WEEK) { vm.setDateFilter(DateFilter.WEEK) } }
            item { ProjectChip(state, vm) }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ProjectChip(state: GalleryUiState, vm: GalleryViewModel) {
    var open by remember { mutableStateOf(false) }
    Box {
        Chip(state.projectFilter ?: stringResource(R.string.filter_project), state.projectFilter != null) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.filter_all)) }, onClick = { vm.setProjectFilter(null); open = false })
            state.projects.forEach { p ->
                DropdownMenuItem(text = { Text(p) }, onClick = { vm.setProjectFilter(p); open = false })
            }
        }
    }
}

@Composable
fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = PsColors.Surface,
            labelColor = PsColors.TextDim,
            selectedContainerColor = PsColors.Accent,
            selectedLabelColor = PsColors.OnAccent,
        ),
        border = FilterChipDefaults.filterChipBorder(enabled = true, selected = selected, borderColor = PsColors.Outline, selectedBorderColor = PsColors.Accent),
    )
}

@Composable
private fun SelectionBar(state: GalleryUiState, vm: GalleryViewModel, onDelete: () -> Unit) {
    var shareMenu by remember { mutableStateOf(false) }
    var zipMenu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().background(PsColors.Surface).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = vm::clearSelection) { Icon(Icons.Outlined.Close, null, tint = PsColors.Text) }
        Text(stringResource(R.string.selected_count, state.selected.size), style = MaterialTheme.typography.titleMedium, color = PsColors.Text, modifier = Modifier.weight(1f))
        IconButton(onClick = vm::selectAll) { Icon(Icons.Outlined.SelectAll, null, tint = PsColors.TextDim) }
        Box {
            IconButton(onClick = { shareMenu = true }) { Icon(Icons.Outlined.Share, stringResource(R.string.share), tint = PsColors.Accent) }
            ShareModeMenu(expanded = shareMenu, stripGps = state.stripGps, onDismiss = { shareMenu = false }) { vm.share(it) }
        }
        Box {
            IconButton(onClick = { zipMenu = true }) { Icon(Icons.Outlined.Archive, stringResource(R.string.export_zip), tint = PsColors.Accent) }
            ShareModeMenu(expanded = zipMenu, stripGps = state.stripGps, onDismiss = { zipMenu = false }, zip = true) { vm.exportZip(it) }
        }
        IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, stringResource(R.string.delete), tint = PsColors.Danger) }
    }
}

@Composable
fun ShareModeMenu(expanded: Boolean, stripGps: Boolean, onDismiss: () -> Unit, zip: Boolean = false, onPick: (ShareMode) -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        val cleanFirst = stripGps
        val items = listOf(
            ShareMode.CLEAN to ("Clean share" to "GPS EXIF removed · stamp & ID kept"),
            ShareMode.ORIGINAL to ("Original" to "Byte-identical · verifiable"),
        ).let { if (cleanFirst) it else it.reversed() }
        items.forEach { (mode, labels) ->
            DropdownMenuItem(
                text = {
                    Column {
                        Text((if (zip) "ZIP · " else "") + labels.first)
                        Text(labels.second, style = MaterialTheme.typography.bodySmall, color = PsColors.TextDim)
                    }
                },
                onClick = { onPick(mode); onDismiss() },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhotoGrid(state: GalleryUiState, onOpen: (String) -> Unit, onToggle: (String) -> Unit) {
    val dayFmt = remember { SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault()) }
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        state.grouped.forEach { (_, photos) ->
            item(span = { GridItemSpan(maxLineSpan) }, key = "h_${photos.first().capturedAt}") {
                Text(
                    dayFmt.format(Date(photos.first().capturedAt)),
                    style = MaterialTheme.typography.labelMedium,
                    color = PsColors.TextDim,
                    modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 4.dp),
                )
            }
            items(photos, key = { it.id }) { photo ->
                val selected = photo.id in state.selected
                PhotoTile(
                    photo = photo,
                    selected = selected,
                    selecting = state.selecting,
                    modifier = Modifier.combinedClickable(
                        onClick = { if (state.selecting) onToggle(photo.id) else onOpen(photo.id) },
                        onLongClick = { onToggle(photo.id) },
                    ),
                )
            }
        }
    }
}

@Composable
fun PhotoTile(photo: PhotoEntity, selected: Boolean, selecting: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(PsColors.Surface)
            .then(if (selected) Modifier.border(2.dp, PsColors.Accent, RoundedCornerShape(12.dp)) else Modifier),
    ) {
        AsyncImage(
            model = File(photo.filePath),
            contentDescription = photo.id,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 6.dp, vertical = 4.dp),
        ) {
            Text(
                Ids.formatCode(photo.verificationCode),
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = Mono),
                color = Color.White,
            )
        }
        photo.sequence?.let {
            Text(
                "#%02d".format(it),
                style = MaterialTheme.typography.labelSmall,
                color = PsColors.OnAccent,
                modifier = Modifier.align(Alignment.TopStart).padding(6.dp).clip(RoundedCornerShape(6.dp)).background(PsColors.Accent).padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
        if (selecting) {
            Icon(
                Icons.Filled.CheckCircle,
                null,
                tint = if (selected) PsColors.Accent else Color.White.copy(alpha = 0.5f),
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp).background(Color.Black.copy(alpha = 0.3f), CircleShape),
            )
        }
    }
}

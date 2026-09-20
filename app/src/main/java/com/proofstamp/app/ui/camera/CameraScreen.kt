package com.proofstamp.app.ui.camera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.concurrent.futures.await
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.FlashAuto
import androidx.compose.material.icons.outlined.FlashOff
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material.icons.outlined.LocationOff
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.proofstamp.app.R
import com.proofstamp.app.di.AppContainer
import com.proofstamp.app.ui.nav.containerViewModel
import com.proofstamp.app.ui.theme.PsColors
import java.io.File
import java.util.concurrent.Executors

@Composable
fun CameraScreen(
    container: AppContainer,
    onOpenGallery: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPhoto: (String) -> Unit,
) {
    val vm = containerViewModel { CameraViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var cameraGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var askedLocation by remember { mutableStateOf(false) }

    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.onLocationPermissionChanged()
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cameraGranted = granted
    }

    LaunchedEffect(cameraGranted, state.settings.gpsEnabled) {
        if (!cameraGranted) {
            cameraLauncher.launch(Manifest.permission.CAMERA)
        } else if (state.settings.gpsEnabled && !container.locationProvider.hasPermission() && !askedLocation) {
            askedLocation = true
            locationLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (!cameraGranted) {
            PermissionRationale(
                onGrant = { cameraLauncher.launch(Manifest.permission.CAMERA) },
                onSettings = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                    )
                },
            )
        } else {
            CameraContent(vm = vm, state = state, onOpenGallery = onOpenGallery, onOpenSettings = onOpenSettings, onOpenPhoto = onOpenPhoto)
        }
    }
}

@Composable
private fun CameraContent(
    vm: CameraViewModel,
    state: CameraUiState,
    onOpenGallery: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPhoto: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { executor.shutdown() } }

    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setResolutionSelector(
                ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build(),
            )
            .build()
    }
    LaunchedEffect(state.flashMode) { imageCapture.flashMode = state.flashMode }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    LaunchedEffect(state.lensFacing) {
        val provider = ProcessCameraProvider.getInstance(context).await()
        val preview = Preview.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build(),
            )
            .build()
            .also { it.surfaceProvider = previewView.surfaceProvider }
        val selector = CameraSelector.Builder().requireLensFacing(state.lensFacing).build()
        provider.unbindAll()
        runCatching { provider.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture) }
    }

    var showSessionDialog by remember { mutableStateOf(false) }
    var showPresetPicker by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        // ---- top bar
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SessionChip(state = state, onClick = { if (state.session == null) showSessionDialog = true else vm.endSession() })
            Spacer(Modifier.weight(1f))
            GlassIconButton(
                icon = when (state.flashMode) {
                    ImageCapture.FLASH_MODE_ON -> Icons.Outlined.FlashOn
                    ImageCapture.FLASH_MODE_AUTO -> Icons.Outlined.FlashAuto
                    else -> Icons.Outlined.FlashOff
                },
                contentDescription = stringResource(R.string.toggle_flash),
                onClick = vm::cycleFlash,
            )
            Spacer(Modifier.width(8.dp))
            GlassIconButton(
                icon = if (state.settings.gpsEnabled) Icons.Outlined.LocationOn else Icons.Outlined.LocationOff,
                contentDescription = stringResource(R.string.toggle_gps),
                tint = if (state.settings.gpsEnabled && state.fix != null) PsColors.Accent else Color.White,
                onClick = { vm.toggleGps() },
            )
            Spacer(Modifier.width(8.dp))
            GlassIconButton(icon = Icons.Outlined.Tune, contentDescription = stringResource(R.string.nav_settings), onClick = onOpenSettings)
        }

        // ---- viewfinder (4:3, overlay drawn with the exact renderer used for the final JPEG)
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF05070A)),
        ) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            val stamp = vm.previewStamp()
            val renderer = vm.renderer
            Canvas(Modifier.fillMaxSize()) {
                drawIntoCanvas { c -> renderer.draw(c.nativeCanvas, size.width.toInt(), size.height.toInt(), stamp) }
            }
            if (state.processing) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = PsColors.Accent)
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.processing), color = Color.White, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            GpsStatus(state, Modifier.align(Alignment.TopStart).padding(12.dp))
        }

        // ---- presets strip
        LazyRow(
            Modifier.fillMaxWidth().padding(vertical = 10.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                PresetChip(label = state.settings.defaultProject.ifBlank { "No preset" }, selected = state.activePreset == null) { vm.selectPreset(null) }
            }
            items(state.presets, key = { it.id }) { p ->
                PresetChip(label = p.name, selected = state.activePreset?.id == p.id) { vm.selectPreset(p.id) }
            }
        }

        // ---- shutter row
        Row(
            Modifier.fillMaxWidth().weight(1f).padding(horizontal = 28.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Thumbnail(state.lastPhoto?.filePath, onClick = { state.lastPhoto?.let { onOpenPhoto(it.id) } ?: onOpenGallery() })
            ShutterButton(enabled = !state.processing) { vm.capture(imageCapture, executor) }
            GlassIconButton(
                icon = Icons.Outlined.Cameraswitch,
                contentDescription = stringResource(R.string.flip_camera),
                onClick = vm::toggleLens,
                size = 52.dp,
            )
        }
    }

    // ---- dialogs
    if (showSessionDialog) {
        SessionDialog(onDismiss = { showSessionDialog = false }, onStart = { vm.startSession(it); showSessionDialog = false })
    }
    state.noteTarget?.let { photo ->
        QuickNoteDialog(initial = photo.note, onSkip = vm::dismissNote, onSave = vm::saveQuickNote)
    }
    state.error?.let { msg ->
        Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.BottomCenter) {
            Snackbar(action = { TextButton(onClick = vm::clearError) { Text("OK") } }) { Text(msg) }
        }
    }
}

@Composable
private fun SessionChip(state: CameraUiState, onClick: () -> Unit) {
    val active = state.session != null
    Row(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (active) PsColors.Accent.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.08f))
            .border(1.dp, if (active) PsColors.Accent.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.15f), RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            if (active) Icons.Outlined.Stop else Icons.Outlined.PlayArrow,
            null,
            tint = if (active) PsColors.Accent else Color.White,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = state.session?.let { "${it.name} · #${"%02d".format(state.nextSequence)}" } ?: stringResource(R.string.start_session),
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
            maxLines = 1,
        )
    }
}

@Composable
private fun GpsStatus(state: CameraUiState, modifier: Modifier) {
    val (text, color) = when {
        !state.settings.gpsEnabled -> stringResource(R.string.gps_off) to Color.White.copy(alpha = 0.6f)
        state.fix == null -> stringResource(R.string.locating) to PsColors.Warn
        else -> "GPS ±${state.fix.accuracyM?.toInt() ?: 0}m" to PsColors.Accent
    }
    Row(
        modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color.Black.copy(alpha = 0.45f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Icons.Filled.Circle, null, tint = color, modifier = Modifier.size(8.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = Color.White)
    }
}

@Composable
private fun PresetChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) PsColors.OnAccent else Color.White,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) PsColors.Accent else Color.White.copy(alpha = 0.08f))
            .border(1.dp, if (selected) PsColors.Accent else Color.White.copy(alpha = 0.12f), RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun GlassIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    tint: Color = Color.White,
    size: androidx.compose.ui.unit.Dp = 42.dp,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.08f))
            .border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape),
    ) { Icon(icon, contentDescription, tint = tint) }
}

@Composable
private fun ShutterButton(enabled: Boolean, onClick: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, tween(120), label = "shutter")
    Box(
        Modifier
            .size(84.dp)
            .scale(scale)
            .clip(CircleShape)
            .border(4.dp, Color.White, CircleShape)
            .padding(7.dp)
            .clip(CircleShape)
            .background(if (enabled) Color.White else Color.White.copy(alpha = 0.4f))
            .clickable(enabled = enabled) { pressed = true; onClick() },
        contentAlignment = Alignment.Center,
    ) {
        LaunchedEffect(pressed) { if (pressed) { kotlinx.coroutines.delay(150); pressed = false } }
        Icon(Icons.Outlined.PhotoCamera, stringResource(R.string.capture), tint = PsColors.Bg, modifier = Modifier.size(26.dp))
    }
}

@Composable
private fun Thumbnail(path: String?, onClick: () -> Unit) {
    Box(
        Modifier
            .size(52.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (path != null) {
            AsyncImage(model = File(path), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(Icons.Outlined.Collections, stringResource(R.string.nav_gallery), tint = Color.White)
        }
    }
}

@Composable
private fun SessionDialog(onDismiss: () -> Unit, onStart: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.start_session)) },
        text = {
            Column {
                Text(
                    "Photos in a session share one Session ID and are numbered #01, #02… so they can be exported as a single report.",
                    style = MaterialTheme.typography.bodySmall,
                    color = PsColors.TextDim,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.session_name_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { Button(onClick = { onStart(name) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.start_session)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun QuickNoteDialog(initial: String, onSkip: () -> Unit, onSave: (String) -> Unit) {
    var note by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onSkip,
        title = { Text(stringResource(R.string.quick_note_title)) },
        text = {
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                placeholder = { Text(stringResource(R.string.quick_note_hint)) },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { Button(onClick = { onSave(note) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onSkip) { Text(stringResource(R.string.skip)) } },
    )
}

@Composable
private fun PermissionRationale(onGrant: () -> Unit, onSettings: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Outlined.PhotoCamera, null, tint = PsColors.Accent, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.camera_permission_title), style = MaterialTheme.typography.headlineSmall, color = Color.White)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.camera_permission_body), style = MaterialTheme.typography.bodyMedium, color = PsColors.TextDim, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onGrant) { Text(stringResource(R.string.grant_permission)) }
        TextButton(onClick = onSettings) { Text(stringResource(R.string.open_settings)) }
    }
}

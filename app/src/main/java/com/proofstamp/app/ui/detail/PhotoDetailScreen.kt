package com.proofstamp.app.ui.detail

import android.app.Activity
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.proofstamp.app.R
import com.proofstamp.app.ads.AdsManager
import com.proofstamp.app.capture.QrGenerator
import com.proofstamp.app.data.c2pa.C2paState
import com.proofstamp.app.data.crypto.Ids
import com.proofstamp.app.data.db.PhotoEntity
import com.proofstamp.app.di.AppContainer
import com.proofstamp.app.share.ShareMode
import com.proofstamp.app.share.VerifyResult
import com.proofstamp.app.ui.components.CredentialsCard
import com.proofstamp.app.ui.components.KeyValueRow
import com.proofstamp.app.ui.components.MediaThumb
import com.proofstamp.app.ui.components.PsCard
import com.proofstamp.app.ui.components.SectionLabel
import com.proofstamp.app.ui.components.StatusPill
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
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class PhotoDetailViewModel(private val container: AppContainer, private val photoId: String) : ViewModel() {
    val photo: StateFlow<PhotoEntity?> = container.photoRepository.observeById(photoId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val stripGps: StateFlow<Boolean> = container.settings.settings.map { it.stripGpsOnShare }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    private val _verify = MutableStateFlow<VerifyResult?>(null)
    val verify: StateFlow<VerifyResult?> = _verify
    private val _share = MutableStateFlow<Pair<Intent, Boolean>?>(null)
    val share: StateFlow<Pair<Intent, Boolean>?> = _share

    fun recheck() = viewModelScope.launch {
        val p = photo.value ?: container.photoRepository.getById(photoId) ?: return@launch
        _verify.value = container.verifier.verifyStored(p)
    }

    fun share(mode: ShareMode) = viewModelScope.launch {
        val p = photo.value ?: return@launch
        val intent = container.exportManager.shareIntent(listOf(p), mode)
        val s = container.settings.current()
        val show = AdsManager.shouldShowInterstitial(s.exportCount, s.lastInterstitialAt)
        if (show) container.settings.markInterstitialShown(System.currentTimeMillis())
        _share.value = intent to show
    }

    fun consumeShare() { _share.value = null }

    fun saveNote(note: String) = viewModelScope.launch { container.photoRepository.updateNote(photoId, note.trim()) }

    fun delete(onDone: () -> Unit) = viewModelScope.launch {
        val p = photo.value ?: return@launch
        container.photoRepository.delete(p)
        p.sessionId?.let { container.sessionRepository.refreshCount(it) }
        onDone()
    }
}

@Composable
fun PhotoDetailScreen(container: AppContainer, photoId: String, onBack: () -> Unit) {
    val vm = containerViewModel(key = photoId) { PhotoDetailViewModel(container, photoId) }
    val photo by vm.photo.collectAsStateWithLifecycle()
    val verify by vm.verify.collectAsStateWithLifecycle()
    val share by vm.share.collectAsStateWithLifecycle()
    val stripGps by vm.stripGps.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var shareMenu by remember { mutableStateOf(false) }
    var editNote by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(photo?.id) { if (photo != null) vm.recheck() }
    LaunchedEffect(share) {
        val (intent, ad) = share ?: return@LaunchedEffect
        context.startActivity(Intent.createChooser(intent, null))
        if (ad) (context as? Activity)?.let { AdsManager.showInterstitial(it) }
        vm.consumeShare()
    }

    val p = photo
    Column(Modifier.fillMaxSize().background(PsColors.Bg)) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, null, tint = PsColors.Text) }
            Text(p?.id ?: "", style = MaterialTheme.typography.titleMedium, color = PsColors.Text, modifier = Modifier.weight(1f))
            Box {
                IconButton(onClick = { shareMenu = true }) { Icon(Icons.Outlined.Share, stringResource(R.string.share), tint = PsColors.Accent) }
                ShareModeMenu(expanded = shareMenu, stripGps = stripGps, onDismiss = { shareMenu = false }) { vm.share(it) }
            }
            IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.Delete, stringResource(R.string.delete), tint = PsColors.Danger) }
        }

        if (p == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = PsColors.Accent) }
            return
        }

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding()) {
            MediaThumb(
                path = p.filePath,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().aspectRatio((p.width.coerceAtLeast(1)).toFloat() / p.height.coerceAtLeast(1)).clip(RoundedCornerShape(16.dp)).background(PsColors.Surface),
            )
            Spacer(Modifier.height(16.dp))

            IntegrityCard(verify, onRecheck = vm::recheck)
            Spacer(Modifier.height(12.dp))

            SectionLabel(stringResource(R.string.c2pa_section))
            PsCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val qr = remember(p.id) { QrGenerator.generate("proofstamp:${p.id}:${p.capturedAt}", 384).asImageBitmap() }
                    Image(bitmap = qr, contentDescription = stringResource(R.string.verified_capture), modifier = Modifier.size(96.dp).clip(RoundedCornerShape(8.dp)))
                    Column(Modifier.weight(1f)) {
                        if (p.c2pa || verify?.c2pa?.state == C2paState.VALID) {
                            StatusPill(stringResource(R.string.verified_capture), PsColors.Accent, icon = Icons.Outlined.Verified)
                            Spacer(Modifier.height(8.dp))
                        }
                        Text(stringResource(R.string.c2pa_qr_body), style = MaterialTheme.typography.bodySmall, color = PsColors.TextDim)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            CredentialsCard(verify?.c2pa)
            Spacer(Modifier.height(12.dp))

            SectionLabel(stringResource(R.string.verification_code))
            PsCard {
                Text(Ids.formatCode(p.verificationCode), style = MaterialTheme.typography.displayLarge.copy(fontFamily = Mono), color = PsColors.Text)
                Spacer(Modifier.height(4.dp))
                Text("Share this code with the receiver — they can look it up in Verify to confirm the photo exists in this device's ledger.", style = MaterialTheme.typography.bodySmall, color = PsColors.TextDim)
            }
            Spacer(Modifier.height(12.dp))

            SectionLabel("Capture")
            PsCard {
                val tz = TimeZone.getTimeZone(p.timeZoneId)
                val fmt = SimpleDateFormat("EEE, dd MMM yyyy · HH:mm:ss zzz", Locale.getDefault()).apply { timeZone = tz }
                KeyValueRow(stringResource(R.string.captured_at), fmt.format(Date(p.capturedAt)))
                if (p.latitude != null && p.longitude != null) {
                    KeyValueRow(stringResource(R.string.location), String.format(Locale.US, "%.6f, %.6f%s", p.latitude, p.longitude, p.accuracyM?.let { "  ±${it.toInt()}m" } ?: ""), mono = true, copyable = true)
                }
                p.placeName?.takeIf { it.isNotBlank() }?.let { KeyValueRow("Place", it) }
                if (p.project.isNotBlank()) KeyValueRow(stringResource(R.string.project), p.project)
                if (p.operator.isNotBlank()) KeyValueRow(stringResource(R.string.operator), p.operator)
                if (p.sessionId != null) KeyValueRow(stringResource(R.string.session), "${p.sessionId}  ·  #%02d".format(p.sequence ?: 0), mono = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { KeyValueRow(stringResource(R.string.note), p.note.ifBlank { "—" }, maxLines = 6) }
                    IconButton(onClick = { editNote = true }) { Icon(Icons.Outlined.Edit, null, tint = PsColors.TextDim) }
                }
            }
            Spacer(Modifier.height(12.dp))

            SectionLabel("Proof")
            PsCard {
                KeyValueRow(stringResource(R.string.sha256), p.contentHash, mono = true, copyable = true, maxLines = 3)
                KeyValueRow("Proof hash", p.proofHash, mono = true, copyable = true, maxLines = 3)
                KeyValueRow(stringResource(R.string.signature), p.signature, mono = true, copyable = true, maxLines = 3)
                KeyValueRow("Device", p.deviceModel)
                KeyValueRow("App", "ProofStamp ${p.appVersion} · ${p.template.lowercase().replaceFirstChar { it.uppercase() }}")
                KeyValueRow("Size", "${p.width}×${p.height} · ${"%.1f".format(p.fileSize / 1024f / 1024f)} MB")
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (editNote && p != null) {
        var note by remember { mutableStateOf(p.note) }
        AlertDialog(
            onDismissRequest = { editNote = false },
            title = { Text(stringResource(R.string.note)) },
            text = {
                Column {
                    OutlinedTextField(value = note, onValueChange = { note = it }, minLines = 2, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text("Notes are stored in the proof record, not painted on the sealed image. Editing them never breaks verification.", style = MaterialTheme.typography.bodySmall, color = PsColors.TextDim)
                }
            },
            confirmButton = { Button(onClick = { vm.saveNote(note); editNote = false }) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton(onClick = { editNote = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete)) },
            text = { Text(stringResource(R.string.delete_photo_confirm)) },
            confirmButton = { Button(onClick = { confirmDelete = false; vm.delete(onBack) }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

private data class IntegrityPresentation(val color: Color, val icon: ImageVector, val title: String, val body: String)

@Composable
private fun presentation(result: VerifyResult?): IntegrityPresentation = when (result) {
    null -> IntegrityPresentation(PsColors.TextDim, Icons.Outlined.Verified, "Checking…", "Recomputing SHA-256 of the stored file.")
    is VerifyResult.Authentic -> IntegrityPresentation(
        PsColors.Accent, Icons.Outlined.Verified, stringResource(R.string.integrity_ok),
        if (result.signatureValid) "Fingerprint matches the sealed record and the on-device signature is valid." else "Fingerprint matches, but the signature could not be validated (key changed?).",
    )
    is VerifyResult.Modified -> IntegrityPresentation(PsColors.Danger, Icons.Outlined.Warning, stringResource(R.string.integrity_failed), "The file's bytes differ from the fingerprint sealed at capture time.")
    is VerifyResult.Missing -> IntegrityPresentation(PsColors.Warn, Icons.Outlined.Warning, stringResource(R.string.integrity_missing), "The sealed file is no longer on this device.")
    is VerifyResult.ExternalValid -> IntegrityPresentation(PsColors.Accent, Icons.Outlined.Verified, stringResource(R.string.verify_external), "Its embedded C2PA credentials verify, but this device's ledger holds no record of it.")
    is VerifyResult.Unknown -> IntegrityPresentation(PsColors.Warn, Icons.Outlined.Warning, stringResource(R.string.verify_unknown), "")
}

@Composable
fun IntegrityCard(result: VerifyResult?, onRecheck: () -> Unit) {
    val (color, icon, title, body) = presentation(result)
    PsCard(accent = color) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, null, tint = color)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = PsColors.Text)
                Text(body, style = MaterialTheme.typography.bodySmall, color = PsColors.TextDim)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusPill(stringResource(R.string.integrity), color)
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onRecheck) { Text(stringResource(R.string.check_integrity)) }
        }
    }
}

package com.proofstamp.app.ui.verify

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.proofstamp.app.R
import com.proofstamp.app.data.crypto.Ids
import com.proofstamp.app.data.db.PhotoEntity
import com.proofstamp.app.di.AppContainer
import com.proofstamp.app.share.VerifyResult
import com.proofstamp.app.ui.components.CredentialsCard
import com.proofstamp.app.ui.components.KeyValueRow
import com.proofstamp.app.ui.components.PsCard
import com.proofstamp.app.ui.components.SectionLabel
import com.proofstamp.app.ui.detail.IntegrityCard
import com.proofstamp.app.ui.nav.containerViewModel
import com.proofstamp.app.ui.theme.Mono
import com.proofstamp.app.ui.theme.PsColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class VerifyUiState(
    val pickedUri: Uri? = null,
    val result: VerifyResult? = null,
    val busy: Boolean = false,
    val code: String = "",
    val codeResult: PhotoEntity? = null,
    val codeSearched: Boolean = false,
)

class VerifyViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(VerifyUiState())
    val state: StateFlow<VerifyUiState> = _state

    fun verify(uri: Uri) = viewModelScope.launch {
        _state.update { it.copy(pickedUri = uri, result = null, busy = true) }
        val r = container.verifier.verifyUri(uri)
        _state.update { it.copy(result = r, busy = false) }
    }

    fun setCode(v: String) = _state.update { it.copy(code = v.uppercase(), codeSearched = false, codeResult = null) }

    fun lookup() = viewModelScope.launch {
        val code = _state.value.code
        if (code.isBlank()) return@launch
        val found = container.verifier.lookupCode(code)
        _state.update { it.copy(codeResult = found, codeSearched = true) }
    }

    /** QR stamped on a capture was scanned — resolve the record and re-verify the file. */
    fun verifyQr(raw: String) = viewModelScope.launch {
        val id = com.proofstamp.app.share.ProofQr.parsePhotoId(raw)
        val code = com.proofstamp.app.share.ProofQr.parseCode(raw)
        val photo = id?.let { container.photoRepository.getById(it) }
            ?: code?.let { container.verifier.lookupCode(it) }
        if (photo == null) {
            _state.update { it.copy(codeSearched = true, codeResult = null, code = code ?: "") }
            return@launch
        }
        _state.update { it.copy(busy = true, result = null, pickedUri = Uri.fromFile(java.io.File(photo.filePath))) }
        val r = container.verifier.verifyStored(photo)
        _state.update { it.copy(result = r, busy = false) }
    }
}

@Composable
fun VerifyScreen(container: AppContainer, onOpenPhoto: (String) -> Unit) {
    val vm = containerViewModel { VerifyViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()
    // OpenDocument (SAF) over PickVisualMedia: picker URIs always serve GPS-EXIF-redacted
    // bytes, which would break hash/manifest verification; document URIs honour
    // ACCESS_MEDIA_LOCATION and return the true bytes.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.verify(uri)
    }

    Column(Modifier.fillMaxSize().background(PsColors.Bg).statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.verify_title), style = MaterialTheme.typography.headlineMedium, color = PsColors.Text)
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.verify_body), style = MaterialTheme.typography.bodyMedium, color = PsColors.TextDim)
        Spacer(Modifier.height(16.dp))

        var showQrScan by remember { androidx.compose.runtime.mutableStateOf(false) }
        Button(
            onClick = { picker.launch(arrayOf("image/*", "video/*")) },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = PsColors.Accent, contentColor = PsColors.OnAccent),
        ) {
            Icon(Icons.Outlined.AddPhotoAlternate, null)
            Spacer(Modifier.padding(4.dp))
            Text(stringResource(R.string.pick_photo))
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { showQrScan = true }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Icon(Icons.Outlined.QrCodeScanner, null)
            Spacer(Modifier.padding(4.dp))
            Text("Scan capture QR")
        }
        if (showQrScan) {
            com.proofstamp.app.ui.components.ScanSheet(
                title = "Scan the QR on a ProfStamp photo",
                mode = com.proofstamp.app.ui.components.ScanMode.BARCODE,
                onDetected = { vm.verifyQr(it); showQrScan = false },
                onDismiss = { showQrScan = false },
            )
        }

        if (state.busy) {
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = PsColors.Accent, trackColor = PsColors.SurfaceHigh)
        }

        state.pickedUri?.let { uri ->
            Spacer(Modifier.height(16.dp))
            AsyncImage(
                model = uri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 10f).clip(RoundedCornerShape(16.dp)).background(PsColors.Surface),
            )
        }

        state.result?.let { r ->
            Spacer(Modifier.height(16.dp))
            IntegrityCard(r, onRecheck = { state.pickedUri?.let(vm::verify) })
            Spacer(Modifier.height(8.dp))
            CredentialsCard(r.c2pa)
            val photo = when (r) {
                is VerifyResult.Authentic -> r.photo
                is VerifyResult.Modified -> r.photo
                is VerifyResult.Missing -> r.photo
                is VerifyResult.ExternalValid -> null
                is VerifyResult.Unknown -> null
            }
            if (photo != null) {
                Spacer(Modifier.height(12.dp))
                MatchedRecord(photo, onOpenPhoto)
            }
            if (r is VerifyResult.Modified) {
                Spacer(Modifier.height(8.dp))
                PsCard {
                    KeyValueRow("Expected", r.photo.contentHash, mono = true, maxLines = 3)
                    KeyValueRow("Actual", r.actualHash, mono = true, maxLines = 3)
                }
            }
            if (r is VerifyResult.Unknown && r.actualHash.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                PsCard { KeyValueRow(stringResource(R.string.sha256), r.actualHash, mono = true, copyable = true, maxLines = 3) }
            }
            if (r is VerifyResult.ExternalValid && r.actualHash.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                PsCard { KeyValueRow(stringResource(R.string.sha256), r.actualHash, mono = true, copyable = true, maxLines = 3) }
            }
            val forensic = (r as? VerifyResult.Unknown)?.forensicMark ?: (r as? VerifyResult.ExternalValid)?.forensicMark
            if (forensic == true) {
                Spacer(Modifier.height(8.dp))
                PsCard(accent = PsColors.Accent) {
                    Text("ProfStamp forensic mark detected in the pixels", color = PsColors.Text, style = MaterialTheme.typography.bodyMedium)
                    (r as? VerifyResult.Unknown)?.embeddedId?.let {
                        KeyValueRow("Embedded ID", it, mono = true)
                    }
                }
            }
        }

        Spacer(Modifier.height(28.dp))
        SectionLabel(stringResource(R.string.verify_by_code))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = state.code,
                onValueChange = vm::setCode,
                placeholder = { Text(stringResource(R.string.verify_code_hint)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = Mono),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Search),
            )
            OutlinedButton(onClick = vm::lookup, modifier = Modifier.height(56.dp)) {
                Icon(Icons.Outlined.Search, null)
                Spacer(Modifier.padding(2.dp))
                Text(stringResource(R.string.lookup))
            }
        }
        if (state.codeSearched) {
            Spacer(Modifier.height(12.dp))
            val found = state.codeResult
            if (found != null) MatchedRecord(found, onOpenPhoto)
            else PsCard(accent = PsColors.Warn) { Text("No record with code ${Ids.formatCode(Ids.normalizeCode(state.code))} on this device.", color = PsColors.Text) }
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun MatchedRecord(photo: PhotoEntity, onOpen: (String) -> Unit) {
    val fmt = SimpleDateFormat("dd MMM yyyy · HH:mm:ss", Locale.getDefault())
    PsCard(onClick = { onOpen(photo.id) }) {
        Text(photo.id, style = MaterialTheme.typography.titleMedium.copy(fontFamily = Mono), color = PsColors.Text)
        Spacer(Modifier.height(6.dp))
        KeyValueRow(stringResource(R.string.captured_at), fmt.format(Date(photo.capturedAt)))
        if (photo.project.isNotBlank()) KeyValueRow(stringResource(R.string.project), photo.project)
        if (photo.sessionId != null) KeyValueRow(stringResource(R.string.session), "${photo.sessionId} · #%02d".format(photo.sequence ?: 0), mono = true)
        KeyValueRow(stringResource(R.string.verification_code), Ids.formatCode(photo.verificationCode), mono = true)
    }
}

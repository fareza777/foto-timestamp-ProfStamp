package com.proofstamp.app.ui.camera

import android.graphics.Bitmap
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.proofstamp.app.capture.CaptureRequest
import com.proofstamp.app.capture.QrGenerator
import com.proofstamp.app.capture.StampData
import com.proofstamp.app.data.db.PhotoEntity
import com.proofstamp.app.data.db.PresetEntity
import com.proofstamp.app.data.db.SessionEntity
import com.proofstamp.app.data.location.GeoFix
import com.proofstamp.app.data.settings.AppSettings
import com.proofstamp.app.di.AppContainer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class CameraUiState(
    val settings: AppSettings = AppSettings(),
    val session: SessionEntity? = null,
    val presets: List<PresetEntity> = emptyList(),
    val activePreset: PresetEntity? = null,
    val fix: GeoFix? = null,
    val placeName: String? = null,
    val locating: Boolean = false,
    val now: Long = System.currentTimeMillis(),
    val lensFacing: Int = CameraSelector.LENS_FACING_BACK,
    val flashMode: Int = ImageCapture.FLASH_MODE_OFF,
    val processing: Boolean = false,
    val lastPhoto: PhotoEntity? = null,
    val noteTarget: PhotoEntity? = null,
    val error: String? = null,
    val nextSequence: Int = 1,
) {
    val project: String get() = activePreset?.project ?: settings.defaultProject
    val operator: String get() = activePreset?.operator?.takeIf { it.isNotBlank() } ?: settings.operator
    val defaultNote: String get() = activePreset?.defaultNote ?: ""
}

class CameraViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(CameraUiState())
    val state: StateFlow<CameraUiState> = _state.asStateFlow()

    val renderer get() = container.overlayRenderer

    /** Placeholder QR used only in the live preview so the layout matches the final stamp. */
    val previewQr: Bitmap by lazy { QrGenerator.generate("proofstamp:preview", 128) }

    private var locationJob: Job? = null
    private var geocodeJob: Job? = null
    private var lastGeocodedAt = 0L

    init {
        viewModelScope.launch {
            combine(
                container.settings.settings,
                container.sessionRepository.observeActive(),
                container.presetRepository.observeAll(),
            ) { settings, session, presets ->
                Triple(settings, session, presets)
            }.collectLatest { (settings, session, presets) ->
                val active = presets.firstOrNull { it.id == settings.activePresetId }
                val next = session?.let { container.sessionRepository.getById(it.id)?.photoCount?.plus(1) } ?: 1
                _state.update { it.copy(settings = settings, session = session, presets = presets, activePreset = active, nextSequence = next) }
                if (settings.gpsEnabled) startLocation() else stopLocation()
            }
        }
        viewModelScope.launch {
            while (isActive) {
                _state.update { it.copy(now = System.currentTimeMillis()) }
                delay(250)
            }
        }
        viewModelScope.launch {
            container.photoRepository.observeAll().collect { list -> _state.update { it.copy(lastPhoto = list.firstOrNull()) } }
        }
    }

    fun onLocationPermissionChanged() {
        if (_state.value.settings.gpsEnabled) startLocation()
    }

    private fun startLocation() {
        if (locationJob?.isActive == true) return
        if (!container.locationProvider.hasPermission()) return
        _state.update { it.copy(locating = true) }
        locationJob = viewModelScope.launch {
            container.locationProvider.fixes()
                .catch { }
                .collect { fix ->
                    _state.update { it.copy(fix = fix, locating = false) }
                    maybeGeocode(fix)
                }
        }
    }

    private fun stopLocation() {
        locationJob?.cancel()
        locationJob = null
        _state.update { it.copy(fix = null, placeName = null, locating = false) }
    }

    private fun maybeGeocode(fix: GeoFix) {
        if (!_state.value.settings.showPlaceName) return
        val now = System.currentTimeMillis()
        if (now - lastGeocodedAt < 30_000 && _state.value.placeName != null) return
        if (geocodeJob?.isActive == true) return
        lastGeocodedAt = now
        geocodeJob = viewModelScope.launch {
            val name = container.locationProvider.placeName(fix.latitude, fix.longitude)
            if (name != null) _state.update { it.copy(placeName = name) }
        }
    }

    fun previewStamp(): StampData {
        val s = _state.value
        return StampData(
            capturedAt = s.now,
            latitude = s.fix?.latitude,
            longitude = s.fix?.longitude,
            accuracyM = s.fix?.accuracyM,
            placeName = s.placeName,
            project = s.project,
            operator = s.operator,
            note = s.defaultNote,
            sessionName = s.session?.name,
            sequence = s.session?.let { s.nextSequence },
            photoId = "PS-••••••••-••••••••",
            verificationCode = "••••-••••",
            qr = if (s.settings.showQr) previewQr else null,
            template = s.settings.template,
            showCoordinates = s.settings.showCoordinates,
            showPlaceName = s.settings.showPlaceName,
        )
    }

    fun toggleLens() = _state.update {
        it.copy(lensFacing = if (it.lensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK)
    }

    fun cycleFlash() = _state.update {
        it.copy(
            flashMode = when (it.flashMode) {
                ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO
                ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
                else -> ImageCapture.FLASH_MODE_OFF
            },
        )
    }

    fun toggleGps() = viewModelScope.launch { container.settings.setGpsEnabled(!_state.value.settings.gpsEnabled) }

    fun selectPreset(id: Long?) = viewModelScope.launch { container.settings.setActivePreset(id) }

    fun startSession(name: String) = viewModelScope.launch {
        val s = _state.value
        container.sessionRepository.start(name, s.project, s.operator)
    }

    fun endSession() = viewModelScope.launch {
        _state.value.session?.let { container.sessionRepository.end(it.id) }
    }

    fun capture(imageCapture: ImageCapture, executor: Executor) {
        if (_state.value.processing) return
        _state.update { it.copy(processing = true, error = null) }
        viewModelScope.launch {
            try {
                val capturedAt = System.currentTimeMillis()
                val bytes = takePicture(imageCapture, executor)
                val s = _state.value
                val photo = container.captureProcessor.process(
                    CaptureRequest(
                        jpegBytes = bytes,
                        capturedAt = capturedAt,
                        fix = s.fix,
                        placeName = s.placeName?.takeIf { s.settings.showPlaceName },
                        settings = s.settings,
                        project = s.project,
                        operator = s.operator,
                        note = s.defaultNote,
                        session = s.session,
                        mirrored = false,
                    ),
                )
                _state.update { it.copy(processing = false, noteTarget = photo) }
            } catch (e: Exception) {
                _state.update { it.copy(processing = false, error = e.message ?: "Capture failed") }
            }
        }
    }

    private suspend fun takePicture(imageCapture: ImageCapture, executor: Executor): ByteArray =
        suspendCancellableCoroutine { cont ->
            imageCapture.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        val buffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        cont.resume(bytes)
                    } catch (e: Exception) {
                        cont.resumeWithException(e)
                    } finally {
                        image.close()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    cont.resumeWithException(exception)
                }
            })
        }

    fun saveQuickNote(note: String) = viewModelScope.launch {
        val target = _state.value.noteTarget ?: return@launch
        if (note.isNotBlank() && note != target.note) container.photoRepository.updateNote(target.id, note.trim())
        _state.update { it.copy(noteTarget = null) }
    }

    fun dismissNote() = _state.update { it.copy(noteTarget = null) }
    fun clearError() = _state.update { it.copy(error = null) }
}

package com.proofstamp.app.ui.camera

import android.content.Context
import android.graphics.Bitmap
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.proofstamp.app.capture.CaptureRequest
import com.proofstamp.app.capture.QrGenerator
import com.proofstamp.app.capture.StampData
import com.proofstamp.app.data.db.PhotoEntity
import com.proofstamp.app.data.db.PresetEntity
import com.proofstamp.app.data.db.SessionEntity
import com.proofstamp.app.data.location.GeoFix
import com.proofstamp.app.capture.VideoRequest
import com.proofstamp.app.data.settings.AppSettings
import com.proofstamp.app.data.settings.CameraMode
import com.proofstamp.app.data.settings.PhotoLook
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
    /** Photo vs verified-video mode. */
    val videoMode: Boolean = false,
    /** Recording state while video mode is active. */
    val recording: Boolean = false,
    val recordingStartedAt: Long = 0L,
    /** Asset/barcode scanned in-app; embedded into the next capture's manifest. */
    val assetCode: String = "",
    /** CameraX extension modes the current lens supports; AUTO always listed. */
    val supportedModes: List<CameraMode> = listOf(CameraMode.AUTO),
    /** Session-scoped note (e.g. OCR of a site label); overrides the preset default. */
    val noteOverride: String? = null,
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

    /** Bound camera instance — enables zoom, exposure and tap-to-focus controls. */
    var camera: Camera? = null

    fun setLook(v: PhotoLook) = viewModelScope.launch { container.settings.setLook(v) }
    fun setCameraMode(v: CameraMode) = viewModelScope.launch { container.settings.setCameraMode(v) }
    fun setForensicMark(v: Boolean) = viewModelScope.launch { container.settings.setForensicMark(v) }
    fun setSensorProof(v: Boolean) = viewModelScope.launch { container.settings.setSensorProof(v) }
    fun setVideoMode(on: Boolean) = _state.update { it.copy(videoMode = on) }
    fun setAssetCode(v: String) = _state.update { it.copy(assetCode = v.trim()) }
    fun setNoteOverride(v: String?) = _state.update { it.copy(noteOverride = v?.trim()?.takeIf { s -> s.isNotBlank() }) }
    fun setSupportedModes(modes: List<CameraMode>) = _state.update { it.copy(supportedModes = modes) }

    fun setZoomRatio(v: Float) {
        val cam = camera ?: return
        val zoom = cam.cameraInfo.zoomState.value ?: return
        cam.cameraControl.setZoomRatio(v.coerceIn(zoom.minZoomRatio, zoom.maxZoomRatio))
    }

    fun setExposureCompensation(stops: Float) {
        val cam = camera ?: return
        val range = cam.cameraInfo.exposureState.exposureCompensationRange
        val step = cam.cameraInfo.exposureState.exposureCompensationStep.toFloat()
        if (step <= 0f) return
        val idx = (stops / step).toInt().coerceIn(range.lower, range.upper)
        cam.cameraControl.setExposureCompensationIndex(idx)
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
                val s = _state.value
                // Snapshot the physical environment in parallel with the shutter —
                // embedded as a signed sensor assertion when enabled.
                val sensors = if (s.settings.sensorProof) container.sensorProbe.capture(s.fix) else null
                val bytes = takePicture(imageCapture, executor)
                val photo = container.captureProcessor.process(
                    CaptureRequest(
                        jpegBytes = bytes,
                        capturedAt = capturedAt,
                        fix = s.fix,
                        placeName = s.placeName?.takeIf { s.settings.showPlaceName },
                        settings = s.settings,
                        project = s.project,
                        operator = s.operator,
                        note = s.noteOverride ?: s.defaultNote,
                        session = s.session,
                        mirrored = false,
                        assetCode = s.assetCode,
                        sensors = sensors?.takeIf { !it.isEmpty() },
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

    // ---------------- verified video ----------------

    private var activeRecording: Recording? = null

    fun toggleRecording(videoCapture: VideoCapture<Recorder>, context: Context, executor: Executor) {
        if (activeRecording != null) {
            activeRecording?.stop()
            activeRecording = null
            return
        }
        val tmp = java.io.File(context.cacheDir, "rec_${System.currentTimeMillis()}.mp4")
        val startedAt = System.currentTimeMillis()
        val pending = videoCapture.output
            .prepareRecording(context, FileOutputOptions.Builder(tmp).build())
            .let { rec ->
                if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                ) rec.withAudioEnabled() else rec
            }
        _state.update { it.copy(recording = true, recordingStartedAt = startedAt, error = null) }
        activeRecording = pending.start(executor) { event ->
            if (event is VideoRecordEvent.Finalize) {
                activeRecording = null
                _state.update { it.copy(recording = false) }
                if (event.hasError()) {
                    tmp.delete()
                    _state.update { it.copy(error = "Recording failed: ${event.error}") }
                } else {
                    sealVideo(tmp, startedAt)
                }
            }
        }
    }

    private fun sealVideo(file: java.io.File, startedAt: Long) {
        viewModelScope.launch {
            _state.update { it.copy(processing = true) }
            try {
                val s = _state.value
                val sensors = if (s.settings.sensorProof) container.sensorProbe.capture(s.fix) else null
                val photo = container.videoProcessor.process(
                    VideoRequest(
                        file = file,
                        capturedAt = startedAt,
                        fix = s.fix,
                        placeName = s.placeName?.takeIf { s.settings.showPlaceName },
                        settings = s.settings,
                        project = s.project,
                        operator = s.operator,
                        note = s.noteOverride ?: s.defaultNote,
                        session = s.session,
                        assetCode = s.assetCode,
                        sensors = sensors?.takeIf { !it.isEmpty() },
                    ),
                )
                _state.update { it.copy(processing = false, noteTarget = photo) }
            } catch (e: Exception) {
                _state.update { it.copy(processing = false, error = e.message ?: "Video sealing failed") }
            }
        }
    }

    fun saveQuickNote(note: String) = viewModelScope.launch {
        val target = _state.value.noteTarget ?: return@launch
        if (note.isNotBlank() && note != target.note) container.photoRepository.updateNote(target.id, note.trim())
        _state.update { it.copy(noteTarget = null) }
    }

    fun dismissNote() = _state.update { it.copy(noteTarget = null) }
    fun clearError() = _state.update { it.copy(error = null) }
}

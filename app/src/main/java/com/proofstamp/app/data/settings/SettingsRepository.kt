package com.proofstamp.app.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class WatermarkTemplate(val label: String, val description: String) {
    CLASSIC("Classic", "Bottom bar with time, place and ID"),
    CARD("Card", "Rounded glass card in the corner"),
    MINIMAL("Minimal", "Two thin lines, maximum photo"),
    REPORT("Report", "Full-width strip with project header"),
}

/** Color treatment applied to the final image at encode time (the credential covers the result). */
enum class PhotoLook(val label: String) {
    NATURAL("Natural"),
    MONO("Mono"),
    DOCUMENT("Document"),
    VIVID("Vivid"),
}

/** Camera processing modes exposed via CameraX extensions where the device supports them. */
enum class CameraMode(val label: String) {
    AUTO("Auto"),
    NIGHT("Night"),
    HDR("HDR"),
    BOKEH("Portrait"),
    FACE_RETOUCH("Face"),
}

data class AppSettings(
    val operator: String = "",
    val defaultProject: String = "",
    val activePresetId: Long? = null,
    val template: WatermarkTemplate = WatermarkTemplate.CLASSIC,
    val showQr: Boolean = true,
    val showCoordinates: Boolean = true,
    val showPlaceName: Boolean = true,
    val gpsEnabled: Boolean = true,
    val stripGpsOnShare: Boolean = true,
    val exportCount: Int = 0,
    val lastInterstitialAt: Long = 0L,
    val onboarded: Boolean = false,
    /** Embed the invisible forensic watermark keyed to the photo ID. */
    val forensicMark: Boolean = true,
    /** Embed the physical sensor snapshot (accel/light/pressure/GNSS) in the manifest. */
    val sensorProof: Boolean = true,
    val look: PhotoLook = PhotoLook.NATURAL,
    val cameraMode: CameraMode = CameraMode.AUTO,
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    private object Keys {
        val OPERATOR = stringPreferencesKey("operator")
        val PROJECT = stringPreferencesKey("default_project")
        val PRESET = longPreferencesKey("active_preset")
        val TEMPLATE = stringPreferencesKey("template")
        val SHOW_QR = booleanPreferencesKey("show_qr")
        val SHOW_COORDS = booleanPreferencesKey("show_coords")
        val SHOW_PLACE = booleanPreferencesKey("show_place")
        val GPS = booleanPreferencesKey("gps_enabled")
        val STRIP_GPS = booleanPreferencesKey("strip_gps_on_share")
        val EXPORT_COUNT = intPreferencesKey("export_count")
        val LAST_INTERSTITIAL = longPreferencesKey("last_interstitial")
        val ONBOARDED = booleanPreferencesKey("onboarded")
        val FORENSIC = booleanPreferencesKey("forensic_mark")
        val SENSOR_PROOF = booleanPreferencesKey("sensor_proof")
        val LOOK = stringPreferencesKey("photo_look")
        val CAM_MODE = stringPreferencesKey("camera_mode")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            operator = p[Keys.OPERATOR] ?: "",
            defaultProject = p[Keys.PROJECT] ?: "",
            activePresetId = p[Keys.PRESET],
            template = p[Keys.TEMPLATE]?.let { runCatching { WatermarkTemplate.valueOf(it) }.getOrNull() }
                ?: WatermarkTemplate.CLASSIC,
            showQr = p[Keys.SHOW_QR] ?: true,
            showCoordinates = p[Keys.SHOW_COORDS] ?: true,
            showPlaceName = p[Keys.SHOW_PLACE] ?: true,
            gpsEnabled = p[Keys.GPS] ?: true,
            stripGpsOnShare = p[Keys.STRIP_GPS] ?: true,
            exportCount = p[Keys.EXPORT_COUNT] ?: 0,
            lastInterstitialAt = p[Keys.LAST_INTERSTITIAL] ?: 0L,
            onboarded = p[Keys.ONBOARDED] ?: false,
            forensicMark = p[Keys.FORENSIC] ?: true,
            sensorProof = p[Keys.SENSOR_PROOF] ?: true,
            look = p[Keys.LOOK]?.let { runCatching { PhotoLook.valueOf(it) }.getOrNull() } ?: PhotoLook.NATURAL,
            cameraMode = p[Keys.CAM_MODE]?.let { runCatching { CameraMode.valueOf(it) }.getOrNull() } ?: CameraMode.AUTO,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setOperator(v: String) = context.dataStore.edit { it[Keys.OPERATOR] = v }
    suspend fun setDefaultProject(v: String) = context.dataStore.edit { it[Keys.PROJECT] = v }
    suspend fun setActivePreset(id: Long?) = context.dataStore.edit {
        if (id == null) it.remove(Keys.PRESET) else it[Keys.PRESET] = id
    }
    suspend fun setTemplate(t: WatermarkTemplate) = context.dataStore.edit { it[Keys.TEMPLATE] = t.name }
    suspend fun setShowQr(v: Boolean) = context.dataStore.edit { it[Keys.SHOW_QR] = v }
    suspend fun setShowCoordinates(v: Boolean) = context.dataStore.edit { it[Keys.SHOW_COORDS] = v }
    suspend fun setShowPlaceName(v: Boolean) = context.dataStore.edit { it[Keys.SHOW_PLACE] = v }
    suspend fun setGpsEnabled(v: Boolean) = context.dataStore.edit { it[Keys.GPS] = v }
    suspend fun setStripGpsOnShare(v: Boolean) = context.dataStore.edit { it[Keys.STRIP_GPS] = v }
    suspend fun setOnboarded() = context.dataStore.edit { it[Keys.ONBOARDED] = true }
    suspend fun setForensicMark(v: Boolean) = context.dataStore.edit { it[Keys.FORENSIC] = v }
    suspend fun setSensorProof(v: Boolean) = context.dataStore.edit { it[Keys.SENSOR_PROOF] = v }
    suspend fun setLook(v: PhotoLook) = context.dataStore.edit { it[Keys.LOOK] = v.name }
    suspend fun setCameraMode(v: CameraMode) = context.dataStore.edit { it[Keys.CAM_MODE] = v.name }

    /** Increments export counter and returns the new value. */
    suspend fun incrementExportCount(): Int {
        var result = 0
        context.dataStore.edit {
            result = (it[Keys.EXPORT_COUNT] ?: 0) + 1
            it[Keys.EXPORT_COUNT] = result
        }
        return result
    }

    suspend fun markInterstitialShown(now: Long) = context.dataStore.edit { it[Keys.LAST_INTERSTITIAL] = now }
}

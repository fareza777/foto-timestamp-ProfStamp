package com.proofstamp.app.data.sensor

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssStatus
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.proofstamp.app.data.location.GeoFix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * One-shot physical-environment snapshot taken at capture time and embedded in
 * the C2PA manifest as `com.proofstamp.sensors`. Sensor vectors correlate with
 * a real handset being physically present; an exported/edited file produced on
 * a desktop can carry none of it.
 */
class SensorProbe(private val context: Context) {
    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val locationManager = context.getSystemService(LocationManager::class.java)

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    data class Snapshot(
        val accelMagnitude: Float?,
        val gyroMagnitude: Float?,
        val lightLux: Float?,
        val pressureHpa: Float?,
        val magneticMagnitude: Float?,
        val satellitesInView: Int?,
        val satellitesUsed: Int?,
        val mockLocation: Boolean?,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            accelMagnitude?.let { put("accel_ms2", it.toDouble()) }
            gyroMagnitude?.let { put("gyro_rads", it.toDouble()) }
            lightLux?.let { put("light_lux", it.toDouble()) }
            pressureHpa?.let { put("pressure_hpa", it.toDouble()) }
            magneticMagnitude?.let { put("mag_ut", it.toDouble()) }
            satellitesInView?.let { put("gnss_in_view", it) }
            satellitesUsed?.let { put("gnss_used", it) }
            mockLocation?.let { put("mock_location_flag", it) }
        }

        fun isEmpty() = listOfNotNull(
            accelMagnitude, gyroMagnitude, lightLux, pressureHpa,
            magneticMagnitude, satellitesInView, mockLocation,
        ).isEmpty()
    }

    /** Samples for ~[windowMs] then returns the last reading per sensor. Never throws. */
    suspend fun capture(fix: GeoFix?, windowMs: Long = 450): Snapshot = withContext(Dispatchers.Default) {
        val accel = latestVector(Sensor.TYPE_ACCELEROMETER, windowMs)
        val gyro = latestVector(Sensor.TYPE_GYROSCOPE, windowMs)
        val light = latestScalar(Sensor.TYPE_LIGHT, windowMs)
        val pressure = latestScalar(Sensor.TYPE_PRESSURE, windowMs)
        val mag = latestVector(Sensor.TYPE_MAGNETIC_FIELD, windowMs)
        val gnss = gnssStats(windowMs)
        Snapshot(
            accelMagnitude = accel?.let { kotlin.math.sqrt(it[0] * it[0] + it[1] * it[1] + it[2] * it[2]) },
            gyroMagnitude = gyro?.let { kotlin.math.sqrt(it[0] * it[0] + it[1] * it[1] + it[2] * it[2]) },
            lightLux = light,
            pressureHpa = pressure,
            magneticMagnitude = mag?.let { kotlin.math.sqrt(it[0] * it[0] + it[1] * it[1] + it[2] * it[2]) },
            satellitesInView = gnss?.first,
            satellitesUsed = gnss?.second,
            mockLocation = fix?.isMock,
        )
    }

    private suspend fun latestVector(type: Int, windowMs: Long): FloatArray? =
        latestEvent(type, windowMs)?.values

    private suspend fun latestScalar(type: Int, windowMs: Long): Float? =
        latestEvent(type, windowMs)?.values?.firstOrNull()

    private suspend fun latestEvent(type: Int, windowMs: Long): SensorEvent? {
        val sensor = sensorManager?.getDefaultSensor(type) ?: return null
        return withTimeoutOrNull(windowMs) {
            callbackFlow {
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) { trySend(event) }
                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
                }
                sensorManager?.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
                awaitClose { sensorManager?.unregisterListener(listener) }
            }.firstOrNull { it != null }
        }
    }

    @SuppressLint("MissingPermission") // guarded by hasLocationPermission()
    private suspend fun gnssStats(windowMs: Long): Pair<Int, Int>? {
        val lm = locationManager ?: return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return null
        if (!hasLocationPermission()) return null
        return withTimeoutOrNull(windowMs) {
            callbackFlow {
                val cb = object : GnssStatus.Callback() {
                    override fun onSatelliteStatusChanged(status: GnssStatus) {
                        var used = 0
                        for (i in 0 until status.satelliteCount) {
                            if (status.usedInFix(i)) used++
                        }
                        trySend(status.satelliteCount to used)
                    }
                }
                runCatching { lm.registerGnssStatusCallback(cb, null) }
                awaitClose { runCatching { lm.unregisterGnssStatusCallback(cb) } }
            }.firstOrNull()
        }
    }
}

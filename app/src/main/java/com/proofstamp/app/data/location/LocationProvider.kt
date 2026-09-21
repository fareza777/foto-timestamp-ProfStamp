package com.proofstamp.app.data.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume

data class GeoFix(
    val latitude: Double,
    val longitude: Double,
    val accuracyM: Float?,
    val altitudeM: Double?,
    val timestamp: Long,
    val placeName: String? = null,
    /** True when the provider self-reports a mocked location (API 31+ `isMock`, else `isFromMockProvider`). */
    val isMock: Boolean? = null,
)

class LocationProvider(private val context: Context) {
    private val client = LocationServices.getFusedLocationProviderClient(context)

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Continuous fixes while the camera is open; emits nothing if permission is missing. */
    @SuppressLint("MissingPermission")
    fun fixes(): Flow<GeoFix> = callbackFlow {
        if (!hasPermission()) {
            close()
            return@callbackFlow
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 4_000L)
            .setMinUpdateIntervalMillis(2_000L)
            .build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { loc ->
                    trySend(
                        GeoFix(
                            latitude = loc.latitude,
                            longitude = loc.longitude,
                            accuracyM = if (loc.hasAccuracy()) loc.accuracy else null,
                            altitudeM = if (loc.hasAltitude()) loc.altitude else null,
                            timestamp = loc.time,
                            isMock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) loc.isMock
                            else @Suppress("DEPRECATION") loc.isFromMockProvider,
                        ),
                    )
                }
            }
        }
        client.lastLocation.addOnSuccessListener { loc ->
            if (loc != null) {
                trySend(
                    GeoFix(loc.latitude, loc.longitude, if (loc.hasAccuracy()) loc.accuracy else null, if (loc.hasAltitude()) loc.altitude else null, loc.time),
                )
            }
        }
        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        awaitClose { client.removeLocationUpdates(callback) }
    }

    /** Reverse geocode; returns null quickly when offline or unsupported. */
    suspend fun placeName(lat: Double, lon: Double): String? = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) return@withContext null
        val geocoder = Geocoder(context, Locale.getDefault())
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                suspendCancellableCoroutine { cont ->
                    geocoder.getFromLocation(lat, lon, 1) { list ->
                        cont.resume(list.firstOrNull()?.let(::formatAddress))
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                geocoder.getFromLocation(lat, lon, 1)?.firstOrNull()?.let(::formatAddress)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun formatAddress(a: android.location.Address): String {
        val parts = listOfNotNull(a.thoroughfare, a.subLocality, a.locality, a.adminArea)
            .filter { it.isNotBlank() }
            .distinct()
        return parts.take(3).joinToString(", ")
    }
}

package com.proofstamp.app.capture

import android.graphics.Bitmap
import com.proofstamp.app.data.settings.WatermarkTemplate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Everything the overlay needs to draw. Built once per capture (and continuously for preview). */
data class StampData(
    val capturedAt: Long,
    val timeZone: TimeZone = TimeZone.getDefault(),
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracyM: Float? = null,
    val placeName: String? = null,
    val project: String = "",
    val operator: String = "",
    val note: String = "",
    val sessionName: String? = null,
    val sequence: Int? = null,
    val photoId: String,
    val verificationCode: String,
    val qr: Bitmap? = null,
    val template: WatermarkTemplate = WatermarkTemplate.CLASSIC,
    val showCoordinates: Boolean = true,
    val showPlaceName: Boolean = true,
) {
    val timeText: String get() = fmt("HH:mm:ss").format(Date(capturedAt))
    val dateText: String get() = fmt("EEE, dd MMM yyyy").format(Date(capturedAt))
    val zoneText: String get() = fmt("zzz").format(Date(capturedAt))
    val isoText: String get() = fmt("yyyy-MM-dd'T'HH:mm:ssXXX").format(Date(capturedAt))

    val coordsText: String?
        get() = if (latitude != null && longitude != null) {
            val acc = accuracyM?.let { " ±${it.toInt()}m" } ?: ""
            String.format(Locale.US, "%.6f, %.6f%s", latitude, longitude, acc)
        } else null

    val sessionText: String?
        get() = sessionName?.let { name ->
            if (sequence != null) String.format(Locale.US, "%s · #%02d", name, sequence) else name
        }

    val identityText: String
        get() = listOf(project, operator).filter { it.isNotBlank() }.joinToString("  ·  ")

    private fun fmt(pattern: String) = SimpleDateFormat(pattern, Locale.getDefault()).apply { timeZone = this@StampData.timeZone }

    /** Payload embedded in the on-photo QR. Kept short so the QR stays scannable at small sizes. */
    fun qrPayload(): String = "proofstamp:$photoId:$capturedAt"
}

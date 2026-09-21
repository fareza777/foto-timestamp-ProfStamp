package com.proofstamp.app.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import com.proofstamp.app.BuildConfig
import com.proofstamp.app.data.c2pa.C2paCaptureClaim
import com.proofstamp.app.data.c2pa.C2paManager
import com.proofstamp.app.data.crypto.Hashing
import com.proofstamp.app.data.crypto.Ids
import com.proofstamp.app.data.crypto.ProofManifest
import com.proofstamp.app.data.crypto.ProofSigner
import com.proofstamp.app.data.db.PhotoEntity
import com.proofstamp.app.data.db.SessionEntity
import com.proofstamp.app.data.location.GeoFix
import com.proofstamp.app.data.repo.PhotoRepository
import com.proofstamp.app.data.repo.SessionRepository
import com.proofstamp.app.data.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class CaptureRequest(
    val jpegBytes: ByteArray,
    val capturedAt: Long,
    val fix: GeoFix?,
    val placeName: String?,
    val settings: AppSettings,
    val project: String,
    val operator: String,
    val note: String,
    val session: SessionEntity?,
    val mirrored: Boolean,
)

/**
 * Turns a raw camera JPEG into a sealed evidence photo:
 * decode → orient → stamp overlay → encode → write EXIF → SHA-256 → sign → persist record.
 */
class CaptureProcessor(
    private val context: Context,
    private val renderer: OverlayRenderer,
    private val signer: ProofSigner,
    private val photos: PhotoRepository,
    private val sessions: SessionRepository,
    private val c2pa: C2paManager,
) {
    suspend fun process(req: CaptureRequest): PhotoEntity = withContext(Dispatchers.Default) {
        val code = Ids.randomCode()
        val photoId = Ids.photoId(req.capturedAt, code)
        val sequence = req.session?.let { sessions.nextSequence(it.id) }

        val oriented = decodeOriented(req.jpegBytes, req.mirrored)
        val stamp = StampData(
            capturedAt = req.capturedAt,
            latitude = req.fix?.latitude,
            longitude = req.fix?.longitude,
            accuracyM = req.fix?.accuracyM,
            placeName = req.placeName,
            project = req.project,
            operator = req.operator,
            note = req.note,
            sessionName = req.session?.name,
            sequence = sequence,
            photoId = photoId,
            verificationCode = Ids.formatCode(code),
            qr = if (req.settings.showQr) QrGenerator.generate("proofstamp:$photoId:${req.capturedAt}") else null,
            template = req.settings.template,
            showCoordinates = req.settings.showCoordinates,
            showPlaceName = req.settings.showPlaceName,
        )
        val stamped = renderer.render(oriented, stamp)
        if (stamped !== oriented) oriented.recycle()

        val file = File(photos.capturesDir, "$photoId.jpg")
        FileOutputStream(file).use { stamped.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        val (w, h) = stamped.width to stamped.height
        stamped.recycle()
        stamp.qr?.recycle()

        writeExif(file, req, photoId, stamp)

        // Embed the C2PA manifest last: it binds to the final file bytes, so it must
        // run after the EXIF rewrite. The credential travels with the file itself and
        // validates in any C2PA verifier — not just this app.
        val c2paSealed = c2pa.signFile(
            file,
            C2paCaptureClaim(
                photoId = photoId,
                verificationCode = Ids.formatCode(code),
                capturedAt = req.capturedAt,
                timeZoneId = TimeZone.getDefault().id,
                fix = req.fix?.takeIf { req.settings.gpsEnabled },
                placeName = req.placeName,
                project = req.project,
                operator = req.operator,
                sessionId = req.session?.id,
                sequence = sequence,
            ),
        )

        val contentHash = Hashing.sha256(file)
        val manifest = ProofManifest(
            photoId = photoId,
            contentHash = contentHash,
            capturedAt = req.capturedAt,
            timeZoneId = TimeZone.getDefault().id,
            latitude = req.fix?.latitude,
            longitude = req.fix?.longitude,
            project = req.project,
            operator = req.operator,
            sessionId = req.session?.id,
            sequence = sequence,
        )
        val proofHash = manifest.proofHash()
        val entity = PhotoEntity(
            id = photoId,
            verificationCode = code,
            filePath = file.absolutePath,
            fileSize = file.length(),
            width = w,
            height = h,
            capturedAt = req.capturedAt,
            timeZoneId = manifest.timeZoneId,
            latitude = req.fix?.latitude,
            longitude = req.fix?.longitude,
            accuracyM = req.fix?.accuracyM,
            altitudeM = req.fix?.altitudeM,
            placeName = req.placeName,
            project = req.project,
            operator = req.operator,
            note = req.note,
            sessionId = req.session?.id,
            sequence = sequence,
            template = req.settings.template.name,
            contentHash = contentHash,
            proofHash = proofHash,
            signature = signer.sign(proofHash),
            publicKey = signer.publicKeyBase64(),
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            appVersion = BuildConfig.VERSION_NAME,
            c2pa = c2paSealed,
        )
        photos.insert(entity)
        entity
    }

    private fun decodeOriented(bytes: ByteArray, mirrored: Boolean): Bitmap {
        val exif = ExifInterface(ByteArrayInputStream(bytes))
        val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888; inMutable = true }
        val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
        }
        if (mirrored) m.postScale(-1f, 1f)
        if (m.isIdentity) return raw
        val out = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
        if (out !== raw) raw.recycle()
        return if (out.isMutable) out else out.copy(Bitmap.Config.ARGB_8888, true).also { out.recycle() }
    }

    private fun writeExif(file: File, req: CaptureRequest, photoId: String, stamp: StampData) {
        val exif = ExifInterface(file.absolutePath)
        val dt = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date(req.capturedAt))
        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, dt)
        exif.setAttribute(ExifInterface.TAG_DATETIME, dt)
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
        exif.setAttribute(ExifInterface.TAG_SOFTWARE, "ProofStamp ${BuildConfig.VERSION_NAME}")
        exif.setAttribute(ExifInterface.TAG_MAKE, Build.MANUFACTURER)
        exif.setAttribute(ExifInterface.TAG_MODEL, Build.MODEL)
        exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, listOf(req.project, req.note).filter { it.isNotBlank() }.joinToString(" - "))
        exif.setAttribute(ExifInterface.TAG_ARTIST, req.operator)
        exif.setAttribute(ExifInterface.TAG_IMAGE_UNIQUE_ID, photoId)
        exif.setAttribute(
            ExifInterface.TAG_USER_COMMENT,
            "ProofStamp;id=$photoId;code=${stamp.verificationCode};ts=${stamp.isoText};session=${req.session?.id ?: ""}",
        )
        val fix = req.fix
        if (fix != null && req.settings.gpsEnabled) {
            exif.setLatLong(fix.latitude, fix.longitude)
            fix.altitudeM?.let { exif.setAltitude(it) }
            exif.setGpsInfo(android.location.Location("proofstamp").apply {
                latitude = fix.latitude; longitude = fix.longitude; time = fix.timestamp
                fix.altitudeM?.let { altitude = it }
            })
        }
        exif.saveAttributes()
    }
}

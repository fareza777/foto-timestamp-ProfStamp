package com.proofstamp.app.capture

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Build
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
import java.io.File
import java.util.TimeZone

class VideoRequest(
    /** Finished MP4 file on disk (CameraX FileOutputOptions output). */
    val file: File,
    val capturedAt: Long,
    val fix: GeoFix?,
    val placeName: String?,
    val settings: AppSettings,
    val project: String,
    val operator: String,
    val note: String,
    val session: SessionEntity?,
    val assetCode: String = "",
    val sensors: com.proofstamp.app.data.sensor.SensorProbe.Snapshot? = null,
)

/**
 * Seals a recorded MP4: embeds the same signed C2PA manifest a photo carries
 * (format "video/mp4"), then records it in the ledger with mediaType=VIDEO.
 * The clip is not overlaid/re-encoded — the credential binds the original bytes.
 */
class VideoProcessor(
    private val context: Context,
    private val signer: ProofSigner,
    private val photos: PhotoRepository,
    private val sessions: SessionRepository,
    private val c2pa: C2paManager,
) {
    suspend fun process(req: VideoRequest): PhotoEntity = withContext(Dispatchers.Default) {
        val code = Ids.randomCode()
        val photoId = Ids.photoId(req.capturedAt, code)
        val sequence = req.session?.let { sessions.nextSequence(it.id) }

        // Move to the canonical captures location under the photo ID.
        val file = File(photos.capturesDir, "$photoId.mp4")
        if (!req.file.renameTo(file)) {
            req.file.inputStream().use { input -> file.outputStream().use { input.copyTo(it) } }
            req.file.delete()
        }

        val (w, h) = probeDimensions(file)

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
                note = req.note,
                assetCode = req.assetCode,
                sensors = if (req.settings.sensorProof) req.sensors?.toJson() else null,
                mimeType = "video/mp4",
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
            mediaType = "VIDEO",
            assetCode = req.assetCode,
        )
        photos.insert(entity)
        entity
    }

    private fun probeDimensions(file: File): Pair<Int, Int> = try {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(file.absolutePath)
        val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        retriever.release()
        w to h
    } catch (_: Exception) {
        0 to 0
    }
}

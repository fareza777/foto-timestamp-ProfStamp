package com.proofstamp.app.share

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import com.proofstamp.app.data.c2pa.C2paManager
import com.proofstamp.app.data.c2pa.C2paReport
import com.proofstamp.app.data.c2pa.C2paState
import com.proofstamp.app.data.crypto.Hashing
import com.proofstamp.app.data.crypto.ProofManifest
import com.proofstamp.app.data.crypto.ProofSigner
import com.proofstamp.app.data.db.PhotoEntity
import com.proofstamp.app.data.repo.PhotoRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

sealed interface VerifyResult {
    /** C2PA Content Credentials read from the file itself, independent of the local ledger. */
    val c2pa: C2paReport?

    /** Bytes identical to sealed original; signature valid. */
    data class Authentic(
        val photo: PhotoEntity,
        val signatureValid: Boolean,
        override val c2pa: C2paReport? = null,
    ) : VerifyResult

    /** We know this photo (via embedded ID / code) but its bytes differ from the sealed hash. */
    data class Modified(
        val photo: PhotoEntity,
        val actualHash: String,
        override val c2pa: C2paReport? = null,
    ) : VerifyResult

    /** Sealed record exists but the file on disk is gone. */
    data class Missing(
        val photo: PhotoEntity,
        override val c2pa: C2paReport? = null,
    ) : VerifyResult

    /** Valid C2PA credentials, but not a capture this device recorded (e.g. another device's photo). */
    data class ExternalValid(
        override val c2pa: C2paReport,
        val actualHash: String,
        /** True when the invisible ProfStamp watermark was detected in the pixels. */
        val forensicMark: Boolean? = null,
    ) : VerifyResult

    /** Nothing in the local ledger matches this file. */
    data class Unknown(
        val actualHash: String,
        override val c2pa: C2paReport? = null,
        /** True when the invisible ProfStamp watermark was detected — the manifest was stripped. */
        val forensicMark: Boolean? = null,
        /** Photo ID recovered from EXIF/watermark when known. */
        val embeddedId: String? = null,
    ) : VerifyResult
}

class Verifier(
    private val context: Context,
    private val photos: PhotoRepository,
    private val signer: ProofSigner,
    private val c2pa: C2paManager,
) {
    /** Re-checks a photo stored by this app against its own sealed record + embedded credentials. */
    suspend fun verifyStored(photo: PhotoEntity): VerifyResult = withContext(Dispatchers.IO) {
        val file = File(photo.filePath)
        if (!file.exists()) return@withContext VerifyResult.Missing(photo)
        val creds = c2pa.read(file)
        val actual = Hashing.sha256(file)
        if (actual != photo.contentHash) return@withContext VerifyResult.Modified(photo, actual, creds)
        VerifyResult.Authentic(photo, signatureValid(photo), creds)
    }

    /**
     * Verifies an arbitrary image the user picked (e.g. one received over WhatsApp).
     * Reads embedded C2PA credentials first, then consults the local ledger — a picked
     * file is never treated as an original capture unless its bytes match a sealed record.
     */
    suspend fun verifyUri(uri: Uri): VerifyResult = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        // MediaProvider redacts GPS EXIF unless ACCESS_MEDIA_LOCATION is held and the
        // stream is opened via MediaStore.setRequireOriginal — but that rewrite breaks
        // one-shot photo-picker grants (SecurityException), so it is applied only to
        // classic content://media/external|internal URIs. Picker URIs honour the
        // permission directly.
        val source = requireOriginal(uri)
        val creds = runCatching { c2pa.read(source) }.getOrElse { c2pa.read(uri) }

        val actual = openSha256(resolver, source) ?: openSha256(resolver, uri)
            ?: return@withContext VerifyResult.Unknown("", creds)

        photos.getByContentHash(actual)?.let {
            return@withContext VerifyResult.Authentic(it, signatureValid(it), creds)
        }

        // Embedded C2PA credentials already prove the file is unchanged since signing —
        // a copy of a ProfStamp photo stays verifiable even off this device.
        if (creds.state == C2paState.VALID) {
            return@withContext VerifyResult.ExternalValid(creds, actual)
        }
        if (creds.state == C2paState.MODIFIED) {
            // Tampered credentials: still try to attribute it to a local record.
            val known = findLocalRecord(resolver, source)
            if (known != null) return@withContext VerifyResult.Modified(known, actual, creds)
            val embeddedId = extractEmbeddedId(resolver, source)
            val mark = embeddedId?.let { detectMark(source, it) }
            return@withContext VerifyResult.Unknown(actual, creds, forensicMark = mark, embeddedId = embeddedId)
        }

        // Bytes changed (or it's a clean-share copy). Try to find which record it came from.
        val known = findLocalRecord(resolver, source)
        if (known != null) return@withContext VerifyResult.Modified(known, actual, creds)

        // Last resort: the invisible forensic watermark. If the file still carries a
        // recoverable photo ID (EXIF) we can check the mark — a stripped-manifest copy
        // of a ProfStamp original still identifies itself as derived from one.
        val embeddedId = extractEmbeddedId(resolver, source)
        val mark = embeddedId?.let { detectMark(source, it) }
        VerifyResult.Unknown(actual, creds, forensicMark = mark, embeddedId = embeddedId)
    }

    /** Extracts the photo ID left in EXIF by the capture pipeline (may be null). */
    private fun extractEmbeddedId(resolver: android.content.ContentResolver, uri: Uri): String? =
        resolver.openInputStream(uri)?.use { input ->
            runCatching {
                val exif = ExifInterface(input)
                exif.getAttribute(ExifInterface.TAG_IMAGE_UNIQUE_ID)
                    ?: exif.getAttribute(ExifInterface.TAG_USER_COMMENT)
                        ?.substringAfter("id=", "")
                        ?.substringBefore(';')
                        ?.takeIf { it.isNotBlank() }
            }.getOrNull()
        }

    /** Correlates the picked image's pixels against the watermark pattern for [photoId]. */
    private suspend fun detectMark(uri: Uri, photoId: String): Boolean? =
        runCatching {
            val bmp = context.contentResolver.openInputStream(uri)?.use {
                android.graphics.BitmapFactory.decodeStream(it)
            } ?: return null
            val score = com.proofstamp.app.capture.WatermarkCodec.detect(bmp, photoId)
            bmp.recycle()
            score >= com.proofstamp.app.capture.WatermarkCodec.DETECT_THRESHOLD
        }.getOrNull()

    private fun openSha256(resolver: android.content.ContentResolver, uri: Uri): String? =
        runCatching { resolver.openInputStream(uri)?.use { Hashing.sha256(it) } }.getOrNull()

    private fun requireOriginal(uri: Uri): Uri {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return uri
        if (uri.authority != MediaStore.AUTHORITY) return uri
        // content://media/picker/... URIs carry a one-shot grant that does not cover
        // the requireOriginal-parameterized variant — use them as-is.
        if (uri.path.orEmpty().startsWith("/picker")) return uri
        return runCatching { MediaStore.setRequireOriginal(uri) }.getOrDefault(uri)
    }

    private suspend fun findLocalRecord(resolver: android.content.ContentResolver, uri: Uri): PhotoEntity? {
        val embeddedId = resolver.openInputStream(uri)?.use { input ->
            runCatching {
                val exif = ExifInterface(input)
                exif.getAttribute(ExifInterface.TAG_IMAGE_UNIQUE_ID)
                    ?: exif.getAttribute(ExifInterface.TAG_USER_COMMENT)
                        ?.substringAfter("id=", "")
                        ?.substringBefore(';')
                        ?.takeIf { it.isNotBlank() }
            }.getOrNull()
        }
        return embeddedId?.let { photos.getById(it) }
    }

    suspend fun lookupCode(code: String): PhotoEntity? = photos.getByCode(code)

    fun signatureValid(photo: PhotoEntity): Boolean {
        val manifest = ProofManifest(
            photoId = photo.id,
            contentHash = photo.contentHash,
            capturedAt = photo.capturedAt,
            timeZoneId = photo.timeZoneId,
            latitude = photo.latitude,
            longitude = photo.longitude,
            project = photo.project,
            operator = photo.operator,
            sessionId = photo.sessionId,
            sequence = photo.sequence,
        )
        if (manifest.proofHash() != photo.proofHash) return false
        return signer.verify(photo.proofHash, photo.signature, photo.publicKey)
    }
}

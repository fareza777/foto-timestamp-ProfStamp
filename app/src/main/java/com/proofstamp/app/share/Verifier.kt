package com.proofstamp.app.share

import android.content.Context
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.proofstamp.app.data.crypto.Hashing
import com.proofstamp.app.data.crypto.ProofManifest
import com.proofstamp.app.data.crypto.ProofSigner
import com.proofstamp.app.data.db.PhotoEntity
import com.proofstamp.app.data.repo.PhotoRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

sealed interface VerifyResult {
    /** Bytes identical to sealed original; signature valid. */
    data class Authentic(val photo: PhotoEntity, val signatureValid: Boolean) : VerifyResult

    /** We know this photo (via embedded ID / code) but its bytes differ from the sealed hash. */
    data class Modified(val photo: PhotoEntity, val actualHash: String) : VerifyResult

    /** Sealed record exists but the file on disk is gone. */
    data class Missing(val photo: PhotoEntity) : VerifyResult

    /** Nothing in the local ledger matches this file. */
    data class Unknown(val actualHash: String) : VerifyResult
}

class Verifier(
    private val context: Context,
    private val photos: PhotoRepository,
    private val signer: ProofSigner,
) {
    /** Re-checks a photo stored by this app against its own sealed record. */
    suspend fun verifyStored(photo: PhotoEntity): VerifyResult = withContext(Dispatchers.IO) {
        val file = File(photo.filePath)
        if (!file.exists()) return@withContext VerifyResult.Missing(photo)
        val actual = Hashing.sha256(file)
        if (actual != photo.contentHash) return@withContext VerifyResult.Modified(photo, actual)
        VerifyResult.Authentic(photo, signatureValid(photo))
    }

    /** Verifies an arbitrary image the user picked (e.g. one received over WhatsApp). */
    suspend fun verifyUri(uri: Uri): VerifyResult = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val actual = resolver.openInputStream(uri)?.use { Hashing.sha256(it) }
            ?: return@withContext VerifyResult.Unknown("")

        photos.getByContentHash(actual)?.let { return@withContext VerifyResult.Authentic(it, signatureValid(it)) }

        // Bytes changed (or it's a clean-share copy). Try to find which record it came from.
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
        val known = embeddedId?.let { photos.getById(it) }
        if (known != null) VerifyResult.Modified(known, actual) else VerifyResult.Unknown(actual)
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

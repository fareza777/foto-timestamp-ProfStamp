package com.proofstamp.app.share

import android.content.Context
import android.net.Uri
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
    ) : VerifyResult

    /** Nothing in the local ledger matches this file. */
    data class Unknown(
        val actualHash: String,
        override val c2pa: C2paReport? = null,
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
        val creds = c2pa.read(uri)

        val actual = resolver.openInputStream(uri)?.use { Hashing.sha256(it) }
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
            val known = findLocalRecord(resolver, uri)
            return@withContext if (known != null) {
                VerifyResult.Modified(known, actual, creds)
            } else {
                VerifyResult.Unknown(actual, creds)
            }
        }

        // Bytes changed (or it's a clean-share copy). Try to find which record it came from.
        val known = findLocalRecord(resolver, uri)
        if (known != null) VerifyResult.Modified(known, actual, creds) else VerifyResult.Unknown(actual, creds)
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

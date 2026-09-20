package com.proofstamp.app.data.crypto

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object Hashing {
    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    fun sha256(file: File): String = file.inputStream().use { sha256(it) }

    fun sha256(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            md.update(buf, 0, n)
        }
        return md.digest().toHex()
    }

    fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}

/**
 * Canonical metadata that is bound into the proof hash. Field order matters:
 * changing it would invalidate every signature, so treat this as a wire format.
 */
data class ProofManifest(
    val photoId: String,
    val contentHash: String,
    val capturedAt: Long,
    val timeZoneId: String,
    val latitude: Double?,
    val longitude: Double?,
    val project: String,
    val operator: String,
    val sessionId: String?,
    val sequence: Int?,
) {
    fun canonical(): String = buildString {
        append("v1|")
        append(photoId).append('|')
        append(contentHash).append('|')
        append(capturedAt).append('|')
        append(timeZoneId).append('|')
        append(latitude?.let { "%.6f".format(Locale.US, it) } ?: "").append('|')
        append(longitude?.let { "%.6f".format(Locale.US, it) } ?: "").append('|')
        append(project).append('|')
        append(operator).append('|')
        append(sessionId ?: "").append('|')
        append(sequence ?: "")
    }

    fun proofHash(): String = Hashing.sha256(canonical().toByteArray(Charsets.UTF_8))
}

object Ids {
    // Crockford-ish base32 without ambiguous chars (0/O, 1/I/L).
    private const val ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"
    private val random = SecureRandom()

    fun randomCode(length: Int = 8): String {
        val sb = StringBuilder(length)
        repeat(length) { sb.append(ALPHABET[random.nextInt(ALPHABET.length)]) }
        return sb.toString()
    }

    /** 7KQ2M9XA -> 7KQ2-M9XA */
    fun formatCode(raw: String): String =
        if (raw.length == 8) "${raw.substring(0, 4)}-${raw.substring(4)}" else raw

    fun normalizeCode(input: String): String =
        input.uppercase(Locale.US).replace("[^2-9A-Z]".toRegex(), "")

    fun photoId(capturedAt: Long, code: String): String {
        val fmt = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return "PS-${fmt.format(Date(capturedAt))}-$code"
    }

    fun sessionId(): String = "SES-" + randomCode(6)
}

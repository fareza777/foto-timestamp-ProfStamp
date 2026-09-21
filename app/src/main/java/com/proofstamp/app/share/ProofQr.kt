package com.proofstamp.app.share

/**
 * Payload carried by the QR stamped on every capture. Both the legacy
 * `proofstamp:<id>:<ts>` form and the richer v1 form are accepted on scan.
 *
 * v1: `proofstamp:v1:<id>:<ts>:<code>` — id resolves a ledger record; code
 * (the human-readable verification code) can be typed into "verify by code".
 */
object ProofQr {
    const val PREFIX = "proofstamp:"

    fun build(photoId: String, capturedAt: Long, code: String): String =
        "proofstamp:v1:$photoId:$capturedAt:$code"

    /** Returns the embedded photo id from any supported payload, or null. */
    fun parsePhotoId(raw: String): String? {
        if (!raw.startsWith(PREFIX)) return null
        val parts = raw.removePrefix(PREFIX).split(":")
        return when {
            parts.size >= 4 && parts[0] == "v1" -> parts[1].takeIf { it.isNotBlank() }
            parts.size >= 2 -> parts[0].takeIf { it.startsWith("PS-") }
            else -> null
        }
    }

    fun parseCode(raw: String): String? {
        if (!raw.startsWith(PREFIX)) return null
        val parts = raw.removePrefix(PREFIX).split(":")
        return if (parts.size >= 4 && parts[0] == "v1") parts[3].takeIf { it.isNotBlank() } else null
    }
}

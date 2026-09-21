package com.proofstamp.app.data.c2pa

import android.content.Context
import android.os.Build
import android.util.Log
import com.proofstamp.app.BuildConfig
import com.proofstamp.app.data.location.GeoFix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.contentauth.c2pa.Builder
import org.contentauth.c2pa.C2PA
import org.contentauth.c2pa.FileStream
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Metadata baked into the C2PA claim for one capture. */
data class C2paCaptureClaim(
    val photoId: String,
    val verificationCode: String,
    val capturedAt: Long,
    val timeZoneId: String,
    val fix: GeoFix?,
    val placeName: String?,
    val project: String,
    val operator: String,
    val sessionId: String?,
    val sequence: Int?,
)

/** What a C2PA verifier can say about a file. */
enum class C2paState {
    /** Credentials present, signature + hard bindings check out. */
    VALID,

    /** Credentials present but validation failed — the file was changed after signing. */
    MODIFIED,

    /** No C2PA manifest embedded (or unreadable as C2PA). */
    ABSENT,

    /** Manifest present but could not be parsed. */
    ERROR,
}

data class C2paReport(
    val state: C2paState,
    /** True when the signing certificate chains to a public trust anchor (ours is self-signed → false). */
    val trusted: Boolean = false,
    val claimGenerator: String? = null,
    val title: String? = null,
    val issuer: String? = null,
    val capturedWhen: String? = null,
    val failureCodes: List<String> = emptyList(),
)

class C2paManager(
    private val context: Context,
    private val signer: C2paSigner,
) {

    /**
     * Embeds a signed C2PA manifest into [file]. The JPEG is rewritten atomically via a
     * sibling temp file. Returns true when the file now carries a manifest.
     */
    suspend fun signFile(file: File, claim: C2paCaptureClaim): Boolean = withContext(Dispatchers.IO) {
        if (!signer.ensureIdentity()) return@withContext false
        val tmp = File(file.parentFile, "${file.name}.c2pa.tmp")
        try {
            signer.newSigner().use { c2paSigner ->
                Builder.fromJson(buildManifestJson(claim)).use { builder ->
                    FileStream(file, FileStream.Mode.READ).use { src ->
                        FileStream(tmp, FileStream.Mode.WRITE).use { dst ->
                            builder.sign("image/jpeg", src, dst, c2paSigner)
                        }
                    }
                }
            }
            if (tmp.isFile && tmp.length() > file.length()) {
                if (!tmp.renameTo(file)) {
                    tmp.inputStream().use { input -> file.outputStream().use { input.copyTo(it) } }
                    tmp.delete()
                }
                true
            } else {
                tmp.delete()
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "C2PA sign failed", e)
            tmp.delete()
            false
        }
    }

    /** Reads + validates the embedded C2PA manifest of [file] — never throws. */
    suspend fun read(file: File): C2paReport = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext C2paReport(C2paState.ABSENT)
        val json = try {
            C2PA.readFile(file.absolutePath)
        } catch (e: Exception) {
            return@withContext if (looksLikeNoManifest(e)) {
                C2paReport(C2paState.ABSENT)
            } else {
                C2paReport(C2paState.ERROR, failureCodes = listOf(e.message ?: "read error"))
            }
        }
        runCatching { parseReport(json) }.getOrElse { C2paReport(C2paState.ERROR) }
    }

    /** Materializes [uri] to a temp file (C2PA needs random access), reads it, cleans up. */
    suspend fun read(uri: android.net.Uri): C2paReport {
        val tmp = File.createTempFile("c2pa_read", ".jpg", context.cacheDir)
        return try {
            val ok = context.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            } != null
            if (!ok) C2paReport(C2paState.ERROR) else read(tmp)
        } finally {
            tmp.delete()
        }
    }

    private fun looksLikeNoManifest(e: Exception): Boolean {
        val msg = e.message?.lowercase(Locale.US) ?: ""
        return listOf("no manifest", "not found", "manifest", "jumbf", "unsupported", "no c2pa")
            .any { it in msg }
    }

    // ------------------------------------------------------------ manifest build

    internal fun buildManifestJson(claim: C2paCaptureClaim): String {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(claim.capturedAt))

        val actions = JSONArray().put(
            JSONObject().apply {
                put("action", "c2pa.created")
                put("when", iso)
                put("digitalSourceType", "http://cv.iptc.org/newscodes/digitalsourcetype/digitalCapture")
            },
        )

        val captureData = JSONObject().apply {
            put("photo_id", claim.photoId)
            put("verification_code", claim.verificationCode)
            put("captured_at_epoch_ms", claim.capturedAt)
            put("captured_at_iso", iso)
            put("timezone", claim.timeZoneId)
            put("app", "ProofStamp ${BuildConfig.VERSION_NAME}")
            put("app_version", BuildConfig.VERSION_NAME)
            put("device", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
            if (claim.project.isNotBlank()) put("project", claim.project)
            if (claim.operator.isNotBlank()) put("operator", claim.operator)
            claim.sessionId?.let { put("session_id", it) }
            claim.sequence?.let { put("sequence", it) }
            claim.placeName?.takeIf { it.isNotBlank() }?.let { put("place_name", it) }
            claim.fix?.let { f ->
                put("latitude", f.latitude)
                put("longitude", f.longitude)
                f.accuracyM?.let { put("accuracy_m", it) }
            }
        }

        val assertions = JSONArray()
            .put(JSONObject().apply { put("label", "c2pa.actions"); put("data", JSONObject().put("actions", actions)) })
            .put(JSONObject().apply { put("label", ASSERTION_LABEL); put("data", captureData) })

        // Standard EXIF assertion so third-party verifiers display time/GPS fields natively.
        val exifData = JSONObject().apply {
            put("@context", JSONObject().put("exif", "http://ns.adobe.com/exif/1.0/"))
            put("exif:DateTimeOriginal", iso)
            claim.fix?.let { f ->
                put("exif:GPSLatitude", f.latitude)
                put("exif:GPSLongitude", f.longitude)
                f.altitudeM?.let { put("exif:GPSAltitude", it) }
                put("exif:GPSTimeStamp", iso)
            }
        }
        assertions.put(JSONObject().apply { put("label", "stds.exif"); put("data", exifData) })

        return JSONObject().apply {
            put("claim_generator", "ProofStamp/${BuildConfig.VERSION_NAME}")
            put(
                "claim_generator_info",
                JSONArray().put(
                    JSONObject().apply {
                        put("name", "ProofStamp")
                        put("version", BuildConfig.VERSION_NAME)
                        put("os", "Android ${Build.VERSION.RELEASE}")
                    },
                ),
            )
            put("title", claim.photoId)
            put("format", "image/jpeg")
            put("instance_id", "xmp:iid:${claim.photoId}")
            put("assertions", assertions)
        }.toString()
    }

    // ------------------------------------------------------------ report parse

    internal fun parseReport(json: String): C2paReport {
        val root = JSONObject(json)
        val manifests = root.optJSONObject("manifests")
        if (manifests == null || manifests.length() == 0) return C2paReport(C2paState.ABSENT)

        val activeLabel = root.optString("active_manifest")
        val active = if (activeLabel.isNotBlank()) manifests.optJSONObject(activeLabel) else null
            ?: manifests.keys().asSequence().firstOrNull()?.let(manifests::optJSONObject)

        val claimGenerator = active?.optString("claim_generator")?.takeIf { it.isNotBlank() }
            ?: active?.optJSONArray("claim_generator_info")?.optJSONObject(0)
                ?.let { info ->
                    listOf(info.optString("name"), info.optString("version"))
                        .filter { it.isNotBlank() }
                        .joinToString("/")
                        .takeIf { it.isNotBlank() }
                }
        val title = active?.optString("title")?.takeIf { it.isNotBlank() }
        val sigInfo = active?.optJSONObject("signature_info")
        val issuer = sigInfo?.optString("issuer")?.takeIf { it.isNotBlank() }
            ?: sigInfo?.optString("common_name")?.takeIf { it.isNotBlank() }
        val capturedWhen = findCapturedWhen(active)

        // New-style results: validation_results.activeManifest.{success,informational,failure}
        // Legacy: validation_status[] list of {code,...} — only errors are listed there.
        val failures = mutableListOf<String>()
        val results = root.optJSONObject("validation_results")
        val activeCodes = results?.optJSONObject("activeManifest")
        val failureArr = activeCodes?.optJSONArray("failure")
        if (failureArr != null) {
            for (i in 0 until failureArr.length()) {
                failureArr.optJSONObject(i)?.optString("code")?.takeIf { it.isNotBlank() }?.let(failures::add)
            }
        }
        val legacy = root.optJSONArray("validation_status")
        if (legacy != null) {
            for (i in 0 until legacy.length()) {
                legacy.optJSONObject(i)?.optString("code")?.takeIf { it.isNotBlank() }?.let { code ->
                    if (code !in failures) failures += code
                }
            }
        }

        val declared = root.optString("validation_state")
        val state = when (declared) {
            "Trusted", "Valid" -> C2paState.VALID
            "Invalid" -> C2paState.MODIFIED
            else -> deriveState(failures, manifests.length() > 0)
        }
        val trusted = declared == "Trusted" || (
            state == C2paState.VALID && failures.isEmpty() &&
                root.optJSONObject("validation_results")
                    ?.optJSONObject("activeManifest")
                    ?.optJSONArray("success")
                    ?.let { arr -> (0 until arr.length()).any { arr.optJSONObject(it)?.optString("code") == "signingCredential.trusted" } } == true
            )
        return C2paReport(
            state = state,
            trusted = trusted,
            claimGenerator = claimGenerator,
            title = title,
            issuer = issuer,
            capturedWhen = capturedWhen,
            failureCodes = failures,
        )
    }

    private fun deriveState(failures: List<String>, hasManifest: Boolean): C2paState {
        if (!hasManifest) return C2paState.ABSENT
        // A lone "untrusted signing credential" is expected for device self-signed certs:
        // the manifest + hashes are valid, just not anchored in the public C2PA trust list.
        val realFailures = failures.filter { it != UNTRUSTED_CODE }
        return if (realFailures.isEmpty()) C2paState.VALID else C2paState.MODIFIED
    }

    private fun findCapturedWhen(active: JSONObject?): String? {
        val assertions = active?.optJSONArray("assertions") ?: return null
        for (i in 0 until assertions.length()) {
            val a = assertions.optJSONObject(i) ?: continue
            // The SDK stores actions under "c2pa.actions" (v1) or "c2pa.actions.v2".
            if (a.optString("label").startsWith("c2pa.actions")) {
                val actions = a.optJSONObject("data")?.optJSONArray("actions") ?: continue
                for (j in 0 until actions.length()) {
                    actions.optJSONObject(j)?.optString("when")?.takeIf { it.isNotBlank() }?.let { return it }
                }
            }
        }
        return null
    }

    companion object {
        private const val TAG = "C2paManager"
        const val ASSERTION_LABEL = "com.proofstamp.capture"
        private const val UNTRUSTED_CODE = "signingCredential.untrusted"
    }
}

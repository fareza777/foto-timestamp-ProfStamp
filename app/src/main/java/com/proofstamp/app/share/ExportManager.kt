package com.proofstamp.app.share

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.proofstamp.app.BuildConfig
import com.proofstamp.app.data.crypto.Ids
import com.proofstamp.app.data.db.PhotoEntity
import com.proofstamp.app.data.db.SessionEntity
import com.proofstamp.app.data.repo.PhotoRepository
import com.proofstamp.app.data.repo.SessionRepository
import com.proofstamp.app.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class ShareMode {
    /** Byte-identical original — the receiver can verify it against the ledger. */
    ORIGINAL,

    /** Strips GPS and device EXIF. Visual stamp + verification ID remain. */
    CLEAN,
}

class ExportManager(
    private val context: Context,
    private val photos: PhotoRepository,
    private val sessions: SessionRepository,
    private val settings: SettingsRepository,
) {
    private val authority = "${context.packageName}.fileprovider"
    private val sharedDir: File get() = File(context.cacheDir, "shared").apply { mkdirs() }

    /** Prepares a shareable copy and returns a content:// Uri. */
    suspend fun prepareShare(photo: PhotoEntity, mode: ShareMode): Uri = withContext(Dispatchers.IO) {
        val src = File(photo.filePath)
        val out = File(sharedDir, "${photo.id}${if (mode == ShareMode.CLEAN) "_clean" else ""}.jpg")
        when (mode) {
            ShareMode.ORIGINAL -> src.copyTo(out, overwrite = true)
            ShareMode.CLEAN -> writeClean(src, out, photo)
        }
        FileProvider.getUriForFile(context, authority, out)
    }

    suspend fun shareIntent(items: List<PhotoEntity>, mode: ShareMode): Intent {
        val uris = ArrayList(items.map { prepareShare(it, mode) })
        settings.incrementExportCount()
        val text = items.joinToString("\n") { "${it.id}  ·  ${Ids.formatCode(it.verificationCode)}" }
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply { putExtra(Intent.EXTRA_STREAM, uris.first()) }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply { putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris) }
        }
        return intent.apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_TEXT, "Verified with ProofStamp\n$text")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** ZIP of the given photos + report.csv + report.txt; returns share intent. */
    suspend fun exportZip(items: List<PhotoEntity>, session: SessionEntity?, mode: ShareMode): Intent = withContext(Dispatchers.IO) {
        val name = (session?.name ?: "proofstamp_export").replace("[^A-Za-z0-9_-]+".toRegex(), "_")
        val zipFile = File(sharedDir, "${name}_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.zip")
        ZipOutputStream(FileOutputStream(zipFile).buffered()).use { zip ->
            items.forEachIndexed { index, p ->
                val prefix = p.sequence?.let { String.format(Locale.US, "%02d_", it) } ?: String.format(Locale.US, "%02d_", index + 1)
                val entryName = "photos/$prefix${p.id}.jpg"
                zip.putNextEntry(ZipEntry(entryName))
                if (mode == ShareMode.CLEAN) {
                    val tmp = File(sharedDir, "tmp_${p.id}.jpg")
                    writeClean(File(p.filePath), tmp, p)
                    tmp.inputStream().use { it.copyTo(zip) }
                    tmp.delete()
                } else {
                    File(p.filePath).inputStream().use { it.copyTo(zip) }
                }
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("report.csv"))
            zip.write(buildCsv(items).toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("report.txt"))
            zip.write(buildReport(items, session, mode).toByteArray())
            zip.closeEntry()
        }
        settings.incrementExportCount()
        val uri = FileProvider.getUriForFile(context, authority, zipFile)
        Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "ProofStamp report — ${session?.name ?: "export"}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** Copies (optionally cleaned) photos into the public Pictures/ProofStamp album. */
    suspend fun saveToGallery(items: List<PhotoEntity>, mode: ShareMode): Int = withContext(Dispatchers.IO) {
        var saved = 0
        items.forEach { p ->
            val src = if (mode == ShareMode.CLEAN) File(sharedDir, "${p.id}_clean.jpg").also { writeClean(File(p.filePath), it, p) } else File(p.filePath)
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "${p.id}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.DATE_TAKEN, p.capturedAt)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/ProofStamp")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val uri = context.contentResolver.insert(collection, values) ?: return@forEach
            context.contentResolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            }
            saved++
        }
        saved
    }

    fun clearCache() {
        sharedDir.listFiles()?.forEach { it.delete() }
    }

    /**
     * Clean Share: re-encodes pixels (dropping every EXIF block, including GPS and device
     * identifiers) and then writes back only the non-sensitive proof tags.
     */
    private fun writeClean(src: File, out: File, photo: PhotoEntity) {
        val bmp: Bitmap = BitmapFactory.decodeFile(src.absolutePath)
        FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        bmp.recycle()
        val exif = ExifInterface(out.absolutePath)
        exif.setAttribute(ExifInterface.TAG_SOFTWARE, "ProofStamp ${BuildConfig.VERSION_NAME}")
        exif.setAttribute(ExifInterface.TAG_IMAGE_UNIQUE_ID, photo.id)
        exif.setAttribute(ExifInterface.TAG_USER_COMMENT, "ProofStamp;id=${photo.id};code=${Ids.formatCode(photo.verificationCode)};clean=1")
        exif.saveAttributes()
    }

    private fun buildCsv(items: List<PhotoEntity>): String {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        val sb = StringBuilder("sequence,photo_id,verification_code,captured_at,latitude,longitude,place,project,operator,note,sha256\n")
        items.forEach { p ->
            iso.timeZone = TimeZone.getTimeZone(p.timeZoneId)
            sb.append(
                listOf(
                    p.sequence?.toString() ?: "", p.id, Ids.formatCode(p.verificationCode), iso.format(Date(p.capturedAt)),
                    p.latitude?.toString() ?: "", p.longitude?.toString() ?: "", p.placeName ?: "",
                    p.project, p.operator, p.note, p.contentHash,
                ).joinToString(",") { csv(it) },
            ).append('\n')
        }
        return sb.toString()
    }

    private fun csv(v: String) = "\"" + v.replace("\"", "\"\"") + "\""

    private fun buildReport(items: List<PhotoEntity>, session: SessionEntity?, mode: ShareMode): String {
        val fmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.getDefault())
        return buildString {
            appendLine("PROOFSTAMP CAPTURE REPORT")
            appendLine("=".repeat(60))
            if (session != null) {
                appendLine("Session      : ${session.name}")
                appendLine("Session ID   : ${session.id}")
                appendLine("Project      : ${session.project}")
                appendLine("Operator     : ${session.operator}")
                appendLine("Started      : ${fmt.format(Date(session.startedAt))}")
                session.endedAt?.let { appendLine("Ended        : ${fmt.format(Date(it))}") }
            }
            appendLine("Photos       : ${items.size}")
            appendLine("Share mode   : ${if (mode == ShareMode.CLEAN) "Clean (GPS EXIF removed)" else "Original (byte-identical)"}")
            appendLine("Generated    : ${fmt.format(Date())}  ·  ProofStamp ${BuildConfig.VERSION_NAME}")
            appendLine()
            items.forEach { p ->
                fmt.timeZone = TimeZone.getTimeZone(p.timeZoneId)
                appendLine("-".repeat(60))
                appendLine("${p.sequence?.let { String.format(Locale.US, "#%02d  ", it) } ?: ""}${p.id}")
                appendLine("  Code      : ${Ids.formatCode(p.verificationCode)}")
                appendLine("  Captured  : ${fmt.format(Date(p.capturedAt))}")
                if (p.latitude != null && p.longitude != null) {
                    appendLine("  GPS       : ${String.format(Locale.US, "%.6f, %.6f", p.latitude, p.longitude)}${p.accuracyM?.let { " (±${it.toInt()} m)" } ?: ""}")
                }
                p.placeName?.takeIf { it.isNotBlank() }?.let { appendLine("  Place     : $it") }
                if (p.note.isNotBlank()) appendLine("  Note      : ${p.note}")
                appendLine("  SHA-256   : ${p.contentHash}")
                appendLine("  Signature : ${p.signature.take(44)}…")
            }
            appendLine("-".repeat(60))
            appendLine()
            appendLine("Verify any photo offline: open ProofStamp → Verify → pick the file.")
            appendLine("If the SHA-256 of the file matches the value above, the image is byte-identical to the original capture.")
        }
    }
}

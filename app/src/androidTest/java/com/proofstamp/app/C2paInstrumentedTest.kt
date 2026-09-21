package com.proofstamp.app

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.proofstamp.app.data.c2pa.C2paCaptureClaim
import com.proofstamp.app.data.c2pa.C2paManager
import com.proofstamp.app.data.c2pa.C2paSigner
import com.proofstamp.app.data.c2pa.C2paState
import com.proofstamp.app.data.location.GeoFix
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile

/**
 * On-device round-trip for C2PA Content Credentials: sign via the Keystore-backed signer,
 * then verify through the same c2pa-rs engine third-party verifiers use.
 */
@RunWith(AndroidJUnit4::class)
class C2paInstrumentedTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val signer = C2paSigner(context)
    private val c2pa = C2paManager(context, signer)

    private fun makeJpeg(name: String): File {
        val bmp = Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(0xFF336699.toInt())
        val f = File(context.cacheDir, name)
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        bmp.recycle()
        return f
    }

    private fun claim(id: String = "PS-TEST-0001") = C2paCaptureClaim(
        photoId = id,
        verificationCode = "7KQ2-M9XA",
        capturedAt = 1_727_000_000_000L,
        timeZoneId = "UTC",
        fix = GeoFix(latitude = -6.2, longitude = 106.8, accuracyM = 4.5f, altitudeM = 12.0, timestamp = 1_727_000_000_000L, placeName = null),
        placeName = "Test Site",
        project = "PRJ",
        operator = "OP",
        sessionId = "SES-ABC123",
        sequence = 1,
    )

    @Test
    fun signEmbedsValidCredential() = runBlocking {
        val f = makeJpeg("c2pa_signed.jpg")
        assertTrue("C2PA signing failed", c2pa.signFile(f, claim()))
        assertTrue("manifest must grow the file", f.length() > 10_000)

        val report = c2pa.read(f)
        assertEquals(C2paState.VALID, report.state)
        assertTrue(report.claimGenerator?.contains("ProofStamp") == true)
        assertEquals("PS-TEST-0001", report.title)

        // The raw reader JSON carries our custom capture assertion for third-party tools.
        val raw = JSONObject(org.contentauth.c2pa.C2PA.readFile(f.absolutePath))
        val manifests = raw.getJSONObject("manifests")
        val active = manifests.getJSONObject(raw.getString("active_manifest"))
        val assertions = active.getJSONArray("assertions")
        var foundCapture = false
        for (i in 0 until assertions.length()) {
            val a = assertions.getJSONObject(i)
            if (a.optString("label") == C2paManager.ASSERTION_LABEL) {
                val data = a.getJSONObject("data")
                assertEquals("PS-TEST-0001", data.getString("photo_id"))
                assertEquals("7KQ2-M9XA", data.getString("verification_code"))
                assertEquals(-6.2, data.getDouble("latitude"), 0.0001)
                foundCapture = true
            }
        }
        assertTrue("com.proofstamp.capture assertion missing", foundCapture)
        assertNotNull(active.optJSONObject("signature_info")?.optString("issuer"))

        // Export a signed sample so it can be pulled and checked by an
        // independent verifier (c2patool / c2pa-python on a desktop host).
        val out = File(targetFilesDir(), "c2pa_signed_sample.jpg")
        f.inputStream().use { i -> out.outputStream().use { i.copyTo(it) } }
        assertTrue(out.isFile && out.length() > 10_000)
    }

    private fun targetFilesDir(): File =
        androidx.test.platform.app.InstrumentationRegistry
            .getInstrumentation().targetContext.filesDir

    @Test
    fun byteIdenticalCopy_staysValid() = runBlocking {
        val f = makeJpeg("c2pa_copy_src.jpg")
        assertTrue(c2pa.signFile(f, claim("PS-TEST-COPY")))
        val copy = File(context.cacheDir, "c2pa_copy.jpg")
        f.inputStream().use { input -> copy.outputStream().use { input.copyTo(it) } }
        assertEquals(C2paState.VALID, c2pa.read(copy).state)
    }

    @Test
    fun tamperedImage_detected() = runBlocking {
        val f = makeJpeg("c2pa_tamper_src.jpg")
        assertTrue(c2pa.signFile(f, claim("PS-TEST-TAMP")))
        // Flip a byte inside entropy-coded data (near end, before the FFD9 EOI marker).
        RandomAccessFile(f, "rw").use { raf ->
            val off = f.length() - 4
            raf.seek(off)
            val b = raf.readByte().toInt()
            raf.seek(off)
            raf.writeByte(b xor 0xFF)
        }
        val report = c2pa.read(f)
        assertEquals(C2paState.MODIFIED, report.state)
        assertTrue(report.failureCodes.isNotEmpty())
    }

    @Test
    fun reencodedDerivative_losesCredential() = runBlocking {
        val f = makeJpeg("c2pa_crop_src.jpg")
        assertTrue(c2pa.signFile(f, claim("PS-TEST-CROP")))
        // Crop + re-encode (what an editor does) — the JUMBF manifest does not survive.
        val bmp = android.graphics.BitmapFactory.decodeFile(f.absolutePath)
        val cropped = Bitmap.createBitmap(bmp, 0, 0, bmp.width / 2, bmp.height)
        val out = File(context.cacheDir, "c2pa_cropped.jpg")
        out.outputStream().use { cropped.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        val report = c2pa.read(out)
        assertEquals(C2paState.ABSENT, report.state)
    }

    @Test
    fun plainJpeg_noCredentials() = runBlocking {
        val f = makeJpeg("c2pa_plain.jpg")
        assertEquals(C2paState.ABSENT, c2pa.read(f).state)
    }
}

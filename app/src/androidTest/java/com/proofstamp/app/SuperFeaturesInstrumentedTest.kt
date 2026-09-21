package com.proofstamp.app

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.proofstamp.app.capture.WatermarkCodec
import com.proofstamp.app.data.c2pa.C2paCaptureClaim
import com.proofstamp.app.data.c2pa.C2paManager
import com.proofstamp.app.data.c2pa.C2paSigner
import com.proofstamp.app.data.c2pa.C2paState
import com.proofstamp.app.data.location.GeoFix
import com.proofstamp.app.data.sensor.SensorProbe
import com.proofstamp.app.share.ProofQr
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.random.Random

/** On-device coverage for the super-camera layer: sensors, watermark, QR, trusted identity. */
@RunWith(AndroidJUnit4::class)
class SuperFeaturesInstrumentedTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val signer = C2paSigner(context)
    private val c2pa = C2paManager(context, signer)

    private fun claim(id: String, sensors: JSONObject? = null, asset: String = "") = C2paCaptureClaim(
        photoId = id,
        verificationCode = "7KQ2M9XA",
        capturedAt = 1_727_000_000_000L,
        timeZoneId = "UTC",
        fix = GeoFix(-6.2, 106.8, 4.5f, 12.0, 1_727_000_000_000L),
        placeName = "Test Site",
        project = "PRJ",
        operator = "OP",
        sessionId = null,
        sequence = null,
        assetCode = asset,
        sensors = sensors,
    )

    // ---------------- sensor-proof bundle ----------------

    @Test
    fun sensorProbe_returnsSnapshot() = runBlocking {
        val snap = SensorProbe(context).capture(fix = null, windowMs = 800)
        // Emulator exposes at least accelerometer; magnitudes are nullable elsewhere.
        assertNotNull(snap)
        val json = snap.toJson()
        assertNotNull(json)
    }

    @Test
    fun sensorsAssertion_embeddedInManifest() = runBlocking {
        val sensors = JSONObject().apply {
            put("accel_ms2", 9.81)
            put("light_lux", 320.0)
            put("gnss_in_view", 9)
            put("mock_location_flag", false)
        }
        val json = c2pa.buildManifestJson(claim("PS-SENSOR-0001", sensors = sensors, asset = "ASSET-42"))
        assertTrue(json.contains("accel_ms2"))
        assertTrue(json.contains("asset_code"))
        assertTrue(json.contains("ASSET-42"))
    }

    // ---------------- forensic watermark ----------------

    private fun noiseBitmap(w: Int = 400, h: Int = 300, seed: Int = 7): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val rng = Random(seed)
        val px = IntArray(w * h) { (0xFF shl 24) or rng.nextInt(0xFFFFFF) }
        bmp.setPixels(px, 0, w, 0, 0, w, h)
        return bmp
    }

    @Test
    fun watermark_detectsEmbedded_rejectsOthers() {
        val bmp = noiseBitmap()
        val id = "PS-MARK-0001"
        WatermarkCodec.embed(bmp, id)
        assertTrue(WatermarkCodec.detect(bmp, id) >= WatermarkCodec.DETECT_THRESHOLD)
        assertFalse(WatermarkCodec.detect(noiseBitmap(seed = 9), id) >= WatermarkCodec.DETECT_THRESHOLD)
        assertFalse(WatermarkCodec.detect(bmp, "PS-OTHER-1") >= WatermarkCodec.DETECT_THRESHOLD)
    }

    @Test
    fun watermark_survivesJpegReencode() {
        val id = "PS-MARK-0002"
        val bmp = noiseBitmap()
        WatermarkCodec.embed(bmp, id)
        val f = File(context.cacheDir, "wm_reenc.jpg")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        val redecoded = android.graphics.BitmapFactory.decodeFile(f.absolutePath)
        assertTrue(
            "mark must survive JPEG q80",
            WatermarkCodec.detect(redecoded, id) >= WatermarkCodec.DETECT_THRESHOLD,
        )
    }

    // ---------------- QR payload ----------------

    @Test
    fun qrPayload_roundTrips() {
        val p = ProofQr.build("PS-20260921-ABCD", 1_700_000_000_000L, "7KQ2M9XA")
        assertEquals("PS-20260921-ABCD", ProofQr.parsePhotoId(p))
        assertEquals("7KQ2M9XA", ProofQr.parseCode(p))
        assertEquals("PS-20260921-ABCD", ProofQr.parsePhotoId("proofstamp:PS-20260921-ABCD:1700000000000"))
        assertNull(ProofQr.parsePhotoId("https://example.com"))
    }

    // ---------------- trusted identity ----------------

    @Test
    fun trustedIdentity_importAndSign() = runBlocking {
        // Generate a standalone EC P-256 cert+key (CAWG stand-in) and import.
        val kpg = java.security.KeyPairGenerator.getInstance("EC")
        kpg.initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
        val kp = kpg.generateKeyPair()

        val now = System.currentTimeMillis()
        val name = org.bouncycastle.asn1.x500.X500Name("CN=Imported Test Identity, O=ProofStamp Test")
        val spki = org.bouncycastle.asn1.x509.SubjectPublicKeyInfo.getInstance(kp.public.encoded)
        val builder = org.bouncycastle.cert.X509v3CertificateBuilder(
            name,
            java.math.BigInteger.valueOf(now),
            java.util.Date(now - 60_000),
            java.util.Date(now + 86_400_000),
            name,
            spki,
        )
        builder.addExtension(
            org.bouncycastle.asn1.x509.Extension.basicConstraints, true,
            org.bouncycastle.asn1.x509.BasicConstraints(false),
        )
        builder.addExtension(
            org.bouncycastle.asn1.x509.Extension.keyUsage, true,
            org.bouncycastle.asn1.x509.KeyUsage(org.bouncycastle.asn1.x509.KeyUsage.digitalSignature),
        )
        builder.addExtension(
            org.bouncycastle.asn1.x509.Extension.extendedKeyUsage, false,
            org.bouncycastle.asn1.x509.ExtendedKeyUsage(
                org.bouncycastle.asn1.x509.KeyPurposeId.getInstance(
                    org.bouncycastle.asn1.ASN1ObjectIdentifier("1.3.6.1.5.5.7.3.36"),
                ),
            ),
        )
        // c2pa-rs cert profile also requires SKI + AKI (mirrors C2paSigner's self-signed cert).
        val extUtils = org.bouncycastle.cert.bc.BcX509ExtensionUtils()
        builder.addExtension(
            org.bouncycastle.asn1.x509.Extension.subjectKeyIdentifier, false,
            extUtils.createSubjectKeyIdentifier(spki),
        )
        builder.addExtension(
            org.bouncycastle.asn1.x509.Extension.authorityKeyIdentifier, false,
            extUtils.createAuthorityKeyIdentifier(spki),
        )
        val contentSigner = org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withECDSA").build(kp.private)
        val cert = org.bouncycastle.cert.jcajce.JcaX509CertificateConverter().getCertificate(builder.build(contentSigner))

        fun pem(tag: String, der: ByteArray): String {
            val b64 = android.util.Base64.encodeToString(der, android.util.Base64.NO_WRAP)
            return "-----BEGIN $tag-----\n${b64.chunked(64).joinToString("\n")}\n-----END $tag-----\n"
        }
        val err = signer.importTrustedIdentity(
            pem("CERTIFICATE", cert.encoded).toByteArray(),
            pem("PRIVATE KEY", kp.private.encoded).toByteArray(),
        )
        try {
            assertNull("import failed: $err", err)
            assertTrue(signer.hasTrustedIdentity())
            assertTrue(signer.identitySubject().contains("Imported Test Identity"))

            val f = File(context.cacheDir, "trusted_sign.jpg")
            val bmp = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(0xFFAA5522.toInt())
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            val signed = c2pa.signFile(f, claim("PS-TRUST-0001"))
            if (!signed) {
                // Surface the underlying SDK error through logcat for diagnosis.
                android.util.Log.e("TST", "signFile failed; signer=${signer.identitySubject()}")
            }
            assertTrue("signFile returned false (see logcat)", signed)
            val report = c2pa.read(f)
            assertEquals(C2paState.VALID, report.state)
            // The imported identity (not the keystore cert) signs the claim — check the
            // raw reader JSON since DN ordering/formatting varies across SDK versions.
            val raw = JSONObject(org.contentauth.c2pa.C2PA.readFile(f.absolutePath))
            android.util.Log.e("TST", "read: $raw")
            val manifests = raw.getJSONObject("manifests")
            val label = raw.getString("active_manifest")
            val sigInfo = manifests.getJSONObject(label).getJSONObject("signature_info")
            android.util.Log.e("TST", "sigInfo: $sigInfo")
            assertTrue(sigInfo.toString(), sigInfo.toString().contains("Imported Test Identity"))
        } finally {
            signer.clearTrustedIdentity()
            assertFalse(signer.hasTrustedIdentity())
        }
    }

    // ---------------- video manifest ----------------

    @Test
    fun videoManifest_buildsForMp4() {
        val videoClaim = claim("PS-VID-0001").copy(mimeType = "video/mp4")
        val json = c2pa.buildManifestJson(videoClaim)
        val obj = JSONObject(json) // org.json escapes "/" — compare parsed values
        assertEquals("video/mp4", obj.getString("format"))
        assertEquals("PS-VID-0001", obj.getString("title"))
    }
}

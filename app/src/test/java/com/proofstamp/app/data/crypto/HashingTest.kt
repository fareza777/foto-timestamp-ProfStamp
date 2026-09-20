package com.proofstamp.app.data.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HashingTest {
    @Test
    fun sha256OfKnownVector() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Hashing.sha256("abc".toByteArray()),
        )
    }

    @Test
    fun fileAndByteHashesAgree() {
        val f = File.createTempFile("proofstamp", ".bin").apply { writeBytes(ByteArray(200_000) { (it % 251).toByte() }) }
        try {
            assertEquals(Hashing.sha256(f.readBytes()), Hashing.sha256(f))
        } finally {
            f.delete()
        }
    }

    @Test
    fun manifestCanonicalIsStableAndOrderSensitive() {
        val m = ProofManifest(
            photoId = "PS-20250920-7KQ2M9XA",
            contentHash = "deadbeef",
            capturedAt = 1_758_345_600_000L,
            timeZoneId = "Asia/Jakarta",
            latitude = -6.2000001,
            longitude = 106.8166667,
            project = "Survey",
            operator = "Fajar",
            sessionId = "SES-ABC234",
            sequence = 3,
        )
        assertEquals(
            "v1|PS-20250920-7KQ2M9XA|deadbeef|1758345600000|Asia/Jakarta|-6.200000|106.816667|Survey|Fajar|SES-ABC234|3",
            m.canonical(),
        )
        assertEquals(m.proofHash(), m.copy().proofHash())
        assertNotEquals(m.proofHash(), m.copy(capturedAt = m.capturedAt + 1).proofHash())
        assertNotEquals(m.proofHash(), m.copy(contentHash = "deadbeee").proofHash())
    }

    @Test
    fun manifestHandlesNulls() {
        val m = ProofManifest("id", "h", 0L, "UTC", null, null, "", "", null, null)
        assertEquals("v1|id|h|0|UTC||||||", m.canonical())
    }

    @Test
    fun idsFormatAndNormalize() {
        assertEquals("7KQ2-M9XA", Ids.formatCode("7KQ2M9XA"))
        assertEquals("7KQ2M9XA", Ids.normalizeCode(" 7kq2-m9xa "))
        assertEquals("PS-20250920-7KQ2M9XA", Ids.photoId(1_758_345_600_000L, "7KQ2M9XA"))
    }

    @Test
    fun randomCodesUseUnambiguousAlphabet() {
        repeat(200) {
            val code = Ids.randomCode()
            assertEquals(8, code.length)
            assertTrue(code, code.all { it in "23456789ABCDEFGHJKMNPQRSTUVWXYZ" })
        }
        assertTrue(Ids.sessionId().startsWith("SES-"))
    }
}

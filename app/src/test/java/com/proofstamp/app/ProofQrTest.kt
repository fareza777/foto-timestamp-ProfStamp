package com.proofstamp.app

import com.proofstamp.app.share.ProofQr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProofQrTest {

    @Test
    fun `v1 payload round-trips id and code`() {
        val payload = ProofQr.build("PS-20260921-ABCD", 1_700_000_000_000L, "7KQ2M9XA")
        assertEquals("PS-20260921-ABCD", ProofQr.parsePhotoId(payload))
        assertEquals("7KQ2M9XA", ProofQr.parseCode(payload))
    }

    @Test
    fun `legacy payload still parses`() {
        assertEquals("PS-20260921-ABCD", ProofQr.parsePhotoId("proofstamp:PS-20260921-ABCD:1700000000000"))
        assertNull(ProofQr.parseCode("proofstamp:PS-20260921-ABCD:1700000000000"))
    }

    @Test
    fun `non proofstamp payload returns null`() {
        assertNull(ProofQr.parsePhotoId("https://example.com/x"))
        assertNull(ProofQr.parsePhotoId("random text"))
    }
}

package com.proofstamp.app.ads

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdsPolicyTest {
    private val now = 10_000_000L

    @Test
    fun neverOnFirstExports() {
        assertFalse(AdsManager.shouldShowInterstitial(0, 0L, now))
        assertFalse(AdsManager.shouldShowInterstitial(1, 0L, now))
        assertFalse(AdsManager.shouldShowInterstitial(2, 0L, now))
    }

    @Test
    fun everyNthExport() {
        assertTrue(AdsManager.shouldShowInterstitial(3, 0L, now))
        assertFalse(AdsManager.shouldShowInterstitial(4, 0L, now))
        assertTrue(AdsManager.shouldShowInterstitial(6, 0L, now))
    }

    @Test
    fun respectsMinimumInterval() {
        val recentlyShown = now - AdsManager.MIN_INTERVAL_MS + 1
        assertFalse(AdsManager.shouldShowInterstitial(3, recentlyShown, now))
        assertTrue(AdsManager.shouldShowInterstitial(3, now - AdsManager.MIN_INTERVAL_MS, now))
    }
}

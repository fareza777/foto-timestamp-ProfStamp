package com.proofstamp.app.ads

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.proofstamp.app.BuildConfig
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ad policy: banners only on Gallery/Settings, never on the camera. An interstitial may show
 * after every [EXPORTS_PER_INTERSTITIAL] exports and never more often than [MIN_INTERVAL_MS].
 */
object AdsManager {
    private const val TAG = "AdsManager"
    const val EXPORTS_PER_INTERSTITIAL = 3
    const val MIN_INTERVAL_MS = 3 * 60 * 1000L

    private val initialized = AtomicBoolean(false)
    private var interstitial: InterstitialAd? = null
    private var loading = false

    fun initialize(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        Executors.newSingleThreadExecutor().execute {
            runCatching { MobileAds.initialize(context) {} }
                .onFailure { Log.w(TAG, "MobileAds init failed", it) }
        }
    }

    fun preloadInterstitial(context: Context) {
        if (interstitial != null || loading) return
        loading = true
        InterstitialAd.load(
            context,
            BuildConfig.ADMOB_INTERSTITIAL_ID,
            AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    loading = false
                    interstitial = ad
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    loading = false
                    interstitial = null
                }
            },
        )
    }

    fun shouldShowInterstitial(exportCount: Int, lastShownAt: Long, now: Long = System.currentTimeMillis()): Boolean =
        exportCount > 0 && exportCount % EXPORTS_PER_INTERSTITIAL == 0 && now - lastShownAt >= MIN_INTERVAL_MS

    /** Shows if one is loaded; returns true if an ad was displayed. */
    fun showInterstitial(activity: Activity, onDismissed: () -> Unit = {}): Boolean {
        val ad = interstitial ?: run {
            preloadInterstitial(activity)
            return false
        }
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                interstitial = null
                preloadInterstitial(activity)
                onDismissed()
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                interstitial = null
                onDismissed()
            }
        }
        ad.show(activity)
        return true
    }
}

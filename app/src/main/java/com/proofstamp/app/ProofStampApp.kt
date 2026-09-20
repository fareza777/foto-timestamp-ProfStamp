package com.proofstamp.app

import android.app.Application
import com.proofstamp.app.ads.AdsManager
import com.proofstamp.app.di.AppContainer

class ProofStampApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        AdsManager.initialize(this)
    }
}

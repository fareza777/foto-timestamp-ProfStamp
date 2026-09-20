package com.proofstamp.app.di

import android.content.Context
import com.proofstamp.app.capture.CaptureProcessor
import com.proofstamp.app.capture.OverlayRenderer
import com.proofstamp.app.data.crypto.ProofSigner
import com.proofstamp.app.data.db.ProofStampDatabase
import com.proofstamp.app.data.location.LocationProvider
import com.proofstamp.app.data.repo.PhotoRepository
import com.proofstamp.app.data.repo.PresetRepository
import com.proofstamp.app.data.repo.SessionRepository
import com.proofstamp.app.data.settings.SettingsRepository
import com.proofstamp.app.share.ExportManager
import com.proofstamp.app.share.Verifier

/** Manual dependency graph — small enough that a DI framework would only add weight. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val database: ProofStampDatabase by lazy { ProofStampDatabase.build(appContext) }
    val settings: SettingsRepository by lazy { SettingsRepository(appContext) }
    val signer: ProofSigner by lazy { ProofSigner() }
    val locationProvider: LocationProvider by lazy { LocationProvider(appContext) }

    val photoRepository: PhotoRepository by lazy { PhotoRepository(appContext, database.photoDao()) }
    val sessionRepository: SessionRepository by lazy { SessionRepository(database.sessionDao(), database.photoDao()) }
    val presetRepository: PresetRepository by lazy { PresetRepository(database.presetDao()) }

    val overlayRenderer: OverlayRenderer by lazy { OverlayRenderer(appContext) }
    val captureProcessor: CaptureProcessor by lazy {
        CaptureProcessor(appContext, overlayRenderer, signer, photoRepository, sessionRepository)
    }
    val verifier: Verifier by lazy { Verifier(appContext, photoRepository, signer) }
    val exportManager: ExportManager by lazy { ExportManager(appContext, photoRepository, sessionRepository, settings) }
}

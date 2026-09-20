package com.proofstamp.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.graphics.toArgb
import com.proofstamp.app.ui.nav.ProofStampNavHost
import com.proofstamp.app.ui.theme.ProofStampTheme
import com.proofstamp.app.ui.theme.PsColors

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(PsColors.Bg.copy(alpha = 0.5f).toArgb()),
        )
        super.onCreate(savedInstanceState)
        setContent {
            ProofStampTheme {
                ProofStampNavHost(container = (application as ProofStampApp).container)
            }
        }
    }
}

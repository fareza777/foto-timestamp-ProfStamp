package com.proofstamp.app.ui.components

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.concurrent.futures.await
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.proofstamp.app.ui.theme.PsColors
import java.util.concurrent.Executors

enum class ScanMode { BARCODE, TEXT }

/**
 * Full-screen scanner sheet: live camera preview + ML Kit analyzer (barcode or
 * OCR text). Binds/unbinds its own use cases so callers just show/hide it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanSheet(
    title: String,
    mode: ScanMode,
    onDetected: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var done by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = PsColors.Bg) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = PsColors.Text)
            Spacer(Modifier.height(4.dp))
            Text(
                if (mode == ScanMode.BARCODE) "Point at a barcode or QR code" else "Point at printed text",
                style = MaterialTheme.typography.bodySmall,
                color = PsColors.TextDim,
            )
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f).clip(RoundedCornerShape(16.dp)).background(Color.Black)) {
                AndroidView(
                    factory = { ctx ->
                        PreviewView(ctx).apply {
                            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        }.also { previewView = it }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                TextButton(onClick = onDismiss) { Text("Cancel", color = PsColors.Accent) }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    LaunchedEffect(previewView) {
        val view = previewView ?: return@LaunchedEffect
        val p = ProcessCameraProvider.getInstance(context).await()
        provider = p
        val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        val barcodeScanner = BarcodeScanning.getClient()
        val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        analysis.setAnalyzer(executor) { proxy ->
            @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
            val media = proxy.image
            if (done || media == null) { proxy.close(); return@setAnalyzer }
            val input = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
            if (mode == ScanMode.BARCODE) {
                barcodeScanner.process(input)
                    .addOnSuccessListener { codes ->
                        val hit = codes.firstOrNull { !it.rawValue.isNullOrBlank() }
                            ?.rawValue?.trim()
                        if (hit != null && !done) { done = true; onDetected(hit) }
                    }
                    .addOnCompleteListener { proxy.close() }
            } else {
                textRecognizer.process(input)
                    .addOnSuccessListener { result ->
                        // Largest line wins — labels and serials tend to dominate the frame.
                        val hit = result.textBlocks
                            .flatMap { it.lines }
                            .map { it.text.trim() }
                            .filter { it.length >= 4 }
                            .maxByOrNull { it.length }
                        if (hit != null && !done) { done = true; onDetected(hit) }
                    }
                    .addOnCompleteListener { proxy.close() }
            }
        }
        runCatching {
            p.unbindAll()
            p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { provider?.unbindAll() }
            executor.shutdown()
        }
    }
}

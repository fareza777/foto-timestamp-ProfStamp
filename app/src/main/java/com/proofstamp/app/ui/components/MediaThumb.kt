package com.proofstamp.app.ui.components

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Image or first video frame — videos are displayed without re-encode. */
@Composable
fun MediaThumb(path: String, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) {
    if (path.endsWith(".mp4")) {
        val frame by produceState<Bitmap?>(null, path) {
            value = withContext(Dispatchers.IO) { videoFrame(path) }
        }
        frame?.let {
            Image(bitmap = it.asImageBitmap(), contentDescription = null, contentScale = contentScale, modifier = modifier)
        } ?: androidx.compose.foundation.layout.Box(modifier)
    } else {
        AsyncImage(model = File(path), contentDescription = null, contentScale = contentScale, modifier = modifier)
    }
}

private fun videoFrame(path: String): Bitmap? = try {
    val r = MediaMetadataRetriever()
    r.setDataSource(path)
    val bmp = r.getFrameAtTime(0)
    r.release()
    bmp
} catch (_: Exception) {
    null
}

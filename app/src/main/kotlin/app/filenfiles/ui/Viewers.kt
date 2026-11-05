package app.filenfiles.ui

import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun ImageViewer(path: String, modifier: Modifier = Modifier) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offX by remember { mutableFloatStateOf(0f) }
    var offY by remember { mutableFloatStateOf(0f) }
    Box(
        modifier.fillMaxSize().pointerInput(Unit) {
            detectTransformGestures { _, pan, zoom, _ ->
                scale = (scale * zoom).coerceIn(1f, 8f)
                if (scale > 1f) { offX += pan.x; offY += pan.y } else { offX = 0f; offY = 0f }
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = File(path),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer(
                scaleX = scale, scaleY = scale, translationX = offX, translationY = offY,
            ),
        )
    }
}

@Composable
fun PdfViewer(path: String, modifier: Modifier = Modifier) {
    val mutex = remember { Mutex() }
    var renderer by remember { mutableStateOf<PdfRenderer?>(null) }
    var pfd by remember { mutableStateOf<ParcelFileDescriptor?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    DisposableEffect(path) {
        try {
            val p = ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
            pfd = p; renderer = PdfRenderer(p)
        } catch (e: Exception) { error = e.message ?: "Cannot open PDF" }
        onDispose { runCatching { renderer?.close() }; runCatching { pfd?.close() } }
    }
    val r = renderer
    when {
        error != null -> Box(modifier.fillMaxSize(), Alignment.Center) { Text(error!!, color = MaterialTheme.colorScheme.error) }
        r == null -> Box(modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        else -> BoxWithConstraints(modifier.fillMaxSize()) {
            val widthPx = with(androidx.compose.ui.platform.LocalDensity.current) { maxWidth.roundToPx() }
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items((0 until r.pageCount).toList(), key = { it }) { index ->
                    var bmp by remember(index, widthPx) { mutableStateOf<Bitmap?>(null) }
                    LaunchedEffect(index, widthPx) {
                        bmp = withContext(Dispatchers.Default) {
                            mutex.withLock {
                                r.openPage(index).use { page ->
                                    val h = (widthPx * page.height.toFloat() / page.width).toInt().coerceAtLeast(1)
                                    Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888).also {
                                        it.eraseColor(AColor.WHITE)
                                        page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    }
                                }
                            }
                        }
                    }
                    val b = bmp
                    if (b != null) Image(b.asImageBitmap(), "Page ${index + 1}", Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
                    else Box(Modifier.fillMaxWidth().height(400.dp), Alignment.Center) { CircularProgressIndicator() }
                }
            }
        }
    }
}

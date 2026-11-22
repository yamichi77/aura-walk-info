package io.github.yamichi77.aurawalkinfo.diagnostics

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Saves the next rendered Compose frame to an app-private PNG when [fileName] becomes non-null.
 * This captures Compose drawing. An embedded view can render on a separate surface.
 */
@Composable
internal fun Modifier.capturePanelOnNextDraw(fileName: String?): Modifier {
    if (fileName == null) return this

    val context = LocalContext.current
    val layer = rememberGraphicsLayer()
    val frameDrawn = remember(fileName) { CompletableDeferred<Unit>() }
    LaunchedEffect(fileName) {
        if (withTimeoutOrNull(5_000L) { frameDrawn.await() } == null) {
            logProbe("capture $fileName: no draw within 5s")
            return@LaunchedEffect
        }
        try {
            saveProbePng(context, fileName, layer.toImageBitmap().asAndroidBitmap())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logProbe("capture $fileName: failed (${error.javaClass.simpleName})")
        }
    }
    return drawWithContent {
        if (frameDrawn.isCompleted || size.width < 1f || size.height < 1f) {
            drawContent()
        } else {
            layer.record { this@drawWithContent.drawContent() }
            drawLayer(layer)
            frameDrawn.complete(Unit)
        }
    }
}

internal suspend fun saveProbePng(context: Context, fileName: String, bitmap: Bitmap) {
    withContext(Dispatchers.IO) {
        context.openFileOutput(fileName, Context.MODE_PRIVATE).use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
    }
    logProbe("capture $fileName: ${bitmap.width}x${bitmap.height}")
}

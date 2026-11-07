package org.example.project.admin.features.documents.presentation.scanner

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import org.example.project.admin.core.util.camera.ScanImage
import org.example.project.admin.features.documents.presentation.scanner.imaging.CanvasPageEncoder
import org.jetbrains.skia.Image

private val log = Logger.withTag("scanner-bitmap")

/** Decodes [image] to an [ImageBitmap] off the main path; null until ready, when [image] is null, or when decoding fails (logged). */
@Composable
fun rememberScanBitmap(image: ScanImage?): State<ImageBitmap?> =
    produceState<ImageBitmap?>(initialValue = null, image) {
        value =
            image?.let {
                try {
                    Image.makeFromEncoded(CanvasPageEncoder().toJpeg(it, 0.8f)).toComposeImageBitmap()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    log.w(e) { "could not build a preview bitmap" }
                    null
                }
            }
    }

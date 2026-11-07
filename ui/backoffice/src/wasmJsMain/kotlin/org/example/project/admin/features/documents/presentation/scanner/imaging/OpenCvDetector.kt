@file:OptIn(ExperimentalWasmJsInterop::class)

package org.example.project.admin.features.documents.presentation.scanner.imaging

import co.touchlab.kermit.Logger
import org.example.project.admin.core.util.camera.CanvasImage
import org.example.project.admin.core.util.camera.ScanImage
import kotlin.js.ExperimentalWasmJsInterop

private val log = Logger.withTag("scanner-detect")

class OpenCvDetector : CornerDetector {
    override suspend fun detect(image: ScanImage): CornerDetection {
        val canvas = (image as CanvasImage).canvas
        val csv = detectQuadJs(OpenCvLoader.load(), canvas)
        val fallback = QuadMath.insetFullImage(image.width.toFloat(), image.height.toFloat())
        if (csv.isEmpty()) {
            log.d { "no page contour; proposing the whole image" }
            return CornerDetection(fallback, found = false)
        }
        val n = csv.split(',').map { it.toFloat() }
        val ordered = QuadMath.order((0..3).map { Pt(n[it * 2], n[it * 2 + 1]) })
        val usable = QuadMath.isValid(ordered, minSeparation = 8f)
        return if (usable) CornerDetection(ordered, found = true) else CornerDetection(fallback, found = false)
    }
}

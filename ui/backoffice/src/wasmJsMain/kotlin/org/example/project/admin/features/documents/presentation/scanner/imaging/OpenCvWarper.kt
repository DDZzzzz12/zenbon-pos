@file:OptIn(ExperimentalWasmJsInterop::class)

package org.example.project.admin.features.documents.presentation.scanner.imaging

import org.example.project.admin.core.util.camera.CanvasImage
import org.example.project.admin.core.util.camera.ScanImage
import kotlin.js.ExperimentalWasmJsInterop

class OpenCvWarper : ImageWarper {
    override suspend fun warp(
        image: ScanImage,
        quad: Quad,
        filter: ScanFilter,
        maxLongEdge: Int,
    ): ScanImage {
        val (w, h) = QuadMath.outputSize(quad, maxLongEdge)
        val pts = quad.toList().joinToString(",") { "${it.x},${it.y}" }
        val mode =
            when (filter) {
                ScanFilter.COLOUR -> 0
                ScanFilter.GREYSCALE -> 1
                ScanFilter.BLACK_WHITE -> 2
            }
        return CanvasImage(warpJs(OpenCvLoader.load(), (image as CanvasImage).canvas, pts, w, h, mode))
    }

    override suspend fun rotate90(image: ScanImage): ScanImage = CanvasImage(rotate90Js((image as CanvasImage).canvas))
}

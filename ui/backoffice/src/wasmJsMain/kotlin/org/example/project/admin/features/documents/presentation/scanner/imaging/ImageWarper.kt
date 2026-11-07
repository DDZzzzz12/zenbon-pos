package org.example.project.admin.features.documents.presentation.scanner.imaging

import org.example.project.admin.core.util.camera.ScanImage

interface ImageWarper {
    /** Perspective-corrects [quad] out of [image] into a rectangle capped at [maxLongEdge], then applies [filter]. */
    suspend fun warp(
        image: ScanImage,
        quad: Quad,
        filter: ScanFilter,
        maxLongEdge: Int,
    ): ScanImage

    /** Rotates [image] clockwise by 90 degrees. */
    suspend fun rotate90(image: ScanImage): ScanImage
}

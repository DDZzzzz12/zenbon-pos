package org.example.project.admin.features.documents.presentation.scanner.imaging

import org.example.project.admin.core.util.camera.ScanImage

/** Proposes the page quad for [image], in [image]'s own pixel coordinates. */
interface CornerDetector {
    /**
     * Never returns "nothing": when no page edge is found the result is [QuadMath.insetFullImage]
     * and [CornerDetection.found] is false, so the screen can say so and the operator adjusts by hand.
     */
    suspend fun detect(image: ScanImage): CornerDetection
}

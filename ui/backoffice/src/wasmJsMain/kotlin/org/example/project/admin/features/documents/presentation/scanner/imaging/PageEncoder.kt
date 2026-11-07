package org.example.project.admin.features.documents.presentation.scanner.imaging

import org.example.project.admin.core.util.camera.ScanImage

interface PageEncoder {
    suspend fun toJpeg(
        image: ScanImage,
        quality: Float,
    ): ByteArray
}

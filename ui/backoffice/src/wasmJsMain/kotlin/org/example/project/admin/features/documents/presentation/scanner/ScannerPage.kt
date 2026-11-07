package org.example.project.admin.features.documents.presentation.scanner

import org.example.project.admin.core.util.camera.ScanImage
import org.example.project.admin.features.documents.presentation.scanner.imaging.Quad
import org.example.project.admin.features.documents.presentation.scanner.imaging.ScanFilter

/** An accepted page: the original is kept so the crop can be redone. */
class ScannerPage(
    val id: Int,
    val original: ScanImage,
    val quad: Quad,
    val filter: ScanFilter,
    /** The final warped, filtered and rotated page: what is exported. */
    val result: ScanImage,
    val rotations: Int,
)

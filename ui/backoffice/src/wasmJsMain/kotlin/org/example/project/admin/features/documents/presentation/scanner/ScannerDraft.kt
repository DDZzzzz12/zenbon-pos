package org.example.project.admin.features.documents.presentation.scanner

import org.example.project.admin.core.util.camera.ScanImage
import org.example.project.admin.features.documents.presentation.scanner.imaging.Quad
import org.example.project.admin.features.documents.presentation.scanner.imaging.ScanFilter

/** The page being adjusted. [replacingId] is set when re-editing an accepted page. */
class ScannerDraft(
    val original: ScanImage,
    val detected: Quad,
    val quad: Quad,
    val found: Boolean,
    val filter: ScanFilter = ScanFilter.COLOUR,
    val rotations: Int = 0,
    val preview: ScanImage? = null,
    val replacingId: Int? = null,
)

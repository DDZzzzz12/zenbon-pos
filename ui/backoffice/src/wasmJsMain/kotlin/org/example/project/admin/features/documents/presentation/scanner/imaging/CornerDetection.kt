package org.example.project.admin.features.documents.presentation.scanner.imaging

/** A proposed page quad; [found] is false when [quad] is only the whole-image fallback. */
class CornerDetection(
    val quad: Quad,
    val found: Boolean,
)

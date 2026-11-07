package org.example.project.admin.features.documents.presentation.scanner.imaging

/** Uniform "contain" fit of an image into a view: view = image * scale + offset. */
data class Fit(
    val scale: Float,
    val offsetX: Float,
    val offsetY: Float,
)

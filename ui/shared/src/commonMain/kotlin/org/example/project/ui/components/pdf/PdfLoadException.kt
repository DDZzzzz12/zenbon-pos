package org.example.project.ui.components.pdf

class PdfLoadException(
    val error: PdfLoadError,
    cause: Throwable? = null,
) : Exception(error.name, cause)

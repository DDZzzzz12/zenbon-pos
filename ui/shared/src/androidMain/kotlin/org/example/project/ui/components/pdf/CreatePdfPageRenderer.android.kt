package org.example.project.ui.components.pdf

actual fun createPdfPageRenderer(): PdfPageRenderer = AndroidPdfPageRenderer()

package org.example.project.ui.components.pdf

/** Creates the platform's [PdfPageRenderer]; each instance holds at most one open document. */
expect fun createPdfPageRenderer(): PdfPageRenderer

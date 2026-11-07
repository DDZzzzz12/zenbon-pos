package org.example.project.admin.features.documents.presentation.scanner.imaging

/** One scanned page: JPEG bytes plus their pixel size. Browsers always encode canvas JPEGs as 3-component, so pages are always DeviceRGB. */
class PdfPage(
    val jpeg: ByteArray,
    val widthPx: Int,
    val heightPx: Int,
)

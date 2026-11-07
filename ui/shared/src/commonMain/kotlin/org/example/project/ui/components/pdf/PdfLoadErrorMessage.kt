package org.example.project.ui.components.pdf

import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.keys.PdfViewerKeys

/** The localized message shown for each [PdfLoadError]. */
fun PdfLoadError.messageKey(): StringKey =
    when (this) {
        PdfLoadError.Corrupt -> PdfViewerKeys.errorCorrupt
        PdfLoadError.PasswordProtected -> PdfViewerKeys.errorPasswordProtected
        PdfLoadError.Empty -> PdfViewerKeys.errorEmpty
        PdfLoadError.NotPdf -> PdfViewerKeys.errorNotPdf
        PdfLoadError.Unauthorized -> PdfViewerKeys.errorUnauthorized
        PdfLoadError.Forbidden -> PdfViewerKeys.errorForbidden
        PdfLoadError.Network -> PdfViewerKeys.errorNetwork
        PdfLoadError.NotFound -> PdfViewerKeys.errorNotFound
        PdfLoadError.Unsupported -> PdfViewerKeys.errorUnsupported
    }

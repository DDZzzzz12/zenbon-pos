package org.example.project.core.presentation.strings.keys

import org.example.project.core.presentation.strings.StringKey

/** Strings of composeApp's document panels (DocumentViewerPanel, EmbeddedDocumentViewer) around the shared viewer. */
object DocumentViewerKeys {
    /** Panel title and save file name when the caller names no document. */
    val previewTitle = StringKey("document_viewer.preview_title")
    val loadFailed = StringKey("document_viewer.load_failed")

    /** {0} = the failure's own message. */
    val loadFailedWithReason = StringKey("document_viewer.load_failed_with_reason")

    // Printing
    val printTitle = StringKey("document_viewer.print_title")
    val printDialogOpened = StringKey("document_viewer.print_dialog_opened")

    /** {0} = the platform's error message. */
    val printFailed = StringKey("document_viewer.print_failed")
    val ippPrinter = StringKey("document_viewer.ipp_printer")
    val ippPrintSent = StringKey("document_viewer.ipp_print_sent")

    // Saving a file the viewer cannot preview
    val fileSaved = StringKey("document_viewer.file_saved")

    /** {0} = the platform's error message. */
    val saveFailed = StringKey("document_viewer.save_failed")
}

package org.example.project.admin.core.presentation.layout

import androidx.compose.ui.unit.Dp
import org.example.project.ui.layout.PaneBreakpoints
import org.example.project.ui.layout.PaneLayout

/** How the document and its data share the content area. */
enum class PdfFormMode {
    /** Two columns: the PDF and the form next to each other. */
    SideBySide,

    /** One pane at a time, chosen with a switch. Phones and the middle widths. */
    Switched,
}

/**
 * Decided from the width the CONTENT has (the shell's sidebar already took its share), through the
 * same [PaneBreakpoints] every other screen uses: two columns from 840dp, anything narrower
 * switches. The old "stacked" middle form is gone on purpose: it left the PDF a strip.
 */
fun pdfFormModeFor(contentWidth: Dp): PdfFormMode =
    if (PaneBreakpoints.layoutFor(contentWidth, maxColumns = 2) is PaneLayout.Columns) {
        PdfFormMode.SideBySide
    } else {
        PdfFormMode.Switched
    }

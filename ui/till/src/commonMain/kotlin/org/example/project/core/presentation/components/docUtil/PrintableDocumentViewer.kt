package org.example.project.core.presentation.components.docUtil

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalPrintshop
import androidx.compose.material.icons.filled.Print
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import org.example.project.core.domain.dto.printer.PrinterDto
import org.example.project.core.presentation.components.notification.DisplayMode
import org.example.project.core.presentation.components.notification.LocalGlobalOverlayViewModel
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.DocumentViewerKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.PdfPrinter
import org.example.project.features.dashboard.presentation.viewmodel.PdfViewerViewModel
import org.example.project.ui.components.PosDropdown
import org.example.project.ui.components.PosDropdownAnchor
import org.example.project.ui.components.PosDropdownItem
import org.example.project.ui.components.PosIconButton
import org.koin.compose.viewmodel.koinViewModel

/**
 * [DocumentFileViewer] with the document panels' print actions in the viewer's own toolbar: the system print
 * dialog and, when the shop has IPP+PDF printers, a printer menu ([PdfViewerViewModel]). Both are off until the
 * viewer has opened the document and while a print is running.
 *
 * The viewer stays composed while printing — a slim progress strip runs over its top edge instead — so the
 * page, zoom and scroll position survive a print. Outcomes go to the global notification queue.
 */
@Composable
internal fun PrintableDocumentViewer(
    document: ViewableDocument,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
) {
    val viewModel: PdfViewerViewModel = koinViewModel()
    LaunchedEffect(Unit) { viewModel.loadIppPdfPrinters() }
    val ippPdfPrinters by viewModel.ippPdfPrinters.collectAsState()
    val isIppPrinting by viewModel.isIppPrinting.collectAsState()
    IppPrintFeedback(viewModel)

    var isSystemPrinting by remember { mutableStateOf(false) }
    val printWithSystemDialog =
        systemPrintAction(document, onPrintingChanged = { isSystemPrinting = it })
    val isBusy = isSystemPrinting || isIppPrinting

    Box(modifier = modifier) {
        DocumentFileViewer(
            document = document,
            modifier = Modifier.fillMaxSize(),
            onBack = onBack,
            extraActions = { ready ->
                val enabled = ready && !isBusy
                PosIconButton(
                    icon = Icons.Filled.Print,
                    contentDescription = str(CommonKeys.systemPrint),
                    onClick = printWithSystemDialog,
                    enabled = enabled,
                )
                if (ippPdfPrinters.isNotEmpty()) {
                    IppPrinterMenuButton(
                        printers = ippPdfPrinters,
                        enabled = enabled,
                        onPrint = { printerId -> viewModel.printToIpp(document.bytes, printerId) },
                    )
                }
            },
        )
        if (isBusy) {
            val printing = str(CommonKeys.printing)
            // raw Material 3 by design: a non-modal strip; PosLoadingOverlay is a dialog that would trap the
            // operator for the length of an IPP request and hide the page they are printing.
            LinearProgressIndicator(
                modifier =
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .semantics { contentDescription = printing },
            )
        }
    }
}

/** The IPP printers, one menu entry each, behind a single toolbar button. */
@Composable
private fun IppPrinterMenuButton(
    printers: List<PrinterDto.PrinterLocationResponseDto>,
    enabled: Boolean,
    onPrint: (printerLocationId: Long) -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    Box {
        PosIconButton(
            icon = Icons.Filled.LocalPrintshop,
            contentDescription = str(DocumentViewerKeys.ippPrinter),
            onClick = { showMenu = true },
            enabled = enabled,
        )
        PosDropdown(
            expanded = showMenu && enabled,
            onDismissRequest = { showMenu = false },
            anchor = PosDropdownAnchor.BottomEnd,
        ) {
            printers.forEach { printer ->
                PosDropdownItem(
                    text = printer.printerLocation,
                    onClick = {
                        showMenu = false
                        onPrint(printer.id)
                    },
                )
            }
        }
    }
}

/** Opens the platform print dialog for [document]; [onPrintingChanged] brackets the call. */
@Composable
private fun systemPrintAction(
    document: ViewableDocument,
    onPrintingChanged: (Boolean) -> Unit,
): () -> Unit {
    val overlay = LocalGlobalOverlayViewModel.current
    val language = LocalAppLanguage.current
    val title = str(DocumentViewerKeys.printTitle)
    val opened = str(DocumentViewerKeys.printDialogOpened)
    return {
        onPrintingChanged(true)
        PdfPrinter.printPdf(
            pdfBytes = document.bytes,
            fileName = document.name,
            onSuccess = {
                onPrintingChanged(false)
                overlay.showSuccess(title = title, message = opened, displayMode = DisplayMode.TOAST)
            },
            onError = { error ->
                onPrintingChanged(false)
                overlay.showWarning(
                    title = title,
                    message = getString(DocumentViewerKeys.printFailed, language, error),
                    displayMode = DisplayMode.TOAST,
                )
            },
        )
    }
}

/** Raises the view model's IPP print outcome once in the global notification queue, then clears it. */
@Composable
private fun IppPrintFeedback(viewModel: PdfViewerViewModel) {
    val overlay = LocalGlobalOverlayViewModel.current
    val ippPrintSuccess by viewModel.ippPrintSuccess.collectAsState()
    val ippPrintError by viewModel.ippPrintError.collectAsState()
    val title = str(DocumentViewerKeys.ippPrinter)

    // The view model's success text is a fixed German literal; the localized message replaces it.
    val sent = str(DocumentViewerKeys.ippPrintSent)
    ippPrintSuccess?.let { message ->
        LaunchedEffect(message) {
            overlay.showSuccess(title = title, message = sent, displayMode = DisplayMode.TOAST)
            viewModel.clearIppPrintSuccess()
        }
    }

    ippPrintError?.let { err ->
        val message = str(err.messageKey, *err.messageArgs.toTypedArray())
        LaunchedEffect(err) {
            overlay.showWarning(title = title, message = message, displayMode = DisplayMode.TOAST)
            viewModel.clearIppPrintError()
        }
    }
}

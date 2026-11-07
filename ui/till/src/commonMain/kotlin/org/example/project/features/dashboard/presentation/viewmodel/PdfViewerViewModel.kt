/**
 * ViewModel for DocumentViewerPanel IPP printing operations.
 *
 * Manages loading of IPP+PDF printer locations and sending PDF documents
 * to selected printers via the IPP protocol. Used internally by DocumentViewerPanel
 * so that any caller of DocumentViewerPanel automatically gets IPP print capability.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.dashboard.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.example.project.core.data.remote.PrinterDiscoveryApiClient
import org.example.project.core.data.remote.PrinterLocationApiClient
import org.example.project.core.domain.dto.printer.PrinterDto
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi
import org.example.project.core.presentation.error.UiError

/**
 * ViewModel for DocumentViewerPanel that handles IPP+PDF printer discovery and printing.
 *
 * Provides state for available IPP+PDF printers and manages the print operation
 * lifecycle including loading state and error/success feedback.
 *
 * @property printerLocationApiClient API client for fetching printer locations
 * @property printerDiscoveryApiClient API client for sending PDF to IPP printers
 */
class PdfViewerViewModel(
    private val printerLocationApiClient: PrinterLocationApiClient,
    private val printerDiscoveryApiClient: PrinterDiscoveryApiClient,
) : ViewModel() {
    private val logger = Logger.Companion.withTag("PdfViewerViewModel")

    private val _ippPdfPrinters = MutableStateFlow<List<PrinterDto.PrinterLocationResponseDto>>(emptyList())
    val ippPdfPrinters: StateFlow<List<PrinterDto.PrinterLocationResponseDto>> = _ippPdfPrinters.asStateFlow()

    private val _isIppPrinting = MutableStateFlow(false)
    val isIppPrinting: StateFlow<Boolean> = _isIppPrinting.asStateFlow()

    private val _ippPrintSuccess = MutableStateFlow<String?>(null)
    val ippPrintSuccess: StateFlow<String?> = _ippPrintSuccess.asStateFlow()

    private val _ippPrintError = MutableStateFlow<UiError?>(null)
    val ippPrintError: StateFlow<UiError?> = _ippPrintError.asStateFlow()

    /**
     * Loads all active printer locations configured as IPP+PDF printers.
     *
     * Fetches all printer locations from the server and filters to only those
     * with printerType == "IPP", ippDocumentFormat == "PDF", and isActive == true.
     */
    fun loadIppPdfPrinters() {
        viewModelScope.launch {
            runCatchingApi { printerLocationApiClient.getAllPrinterLocations() }
                .onSuccess { locations ->
                    val ippPdfLocations =
                        locations.filter { location ->
                            location.isActive &&
                                location.printerSetup?.printerType == "IPP" &&
                                location.printerSetup?.ippDocumentFormat == "PDF"
                        }
                    _ippPdfPrinters.value = ippPdfLocations
                    logger.i { "Loaded ${ippPdfLocations.size} IPP+PDF printers" }
                }.onError { uiError ->
                    logger.e { "Failed to load printer locations: $uiError" }
                    _ippPdfPrinters.value = emptyList()
                }
        }
    }

    /**
     * Prints the given PDF bytes to an IPP+PDF printer.
     *
     * Sends the raw PDF data to the server which forwards it to the
     * specified printer location via IPP protocol.
     *
     * @param pdfBytes Raw PDF document bytes
     * @param printerLocationId ID of the printer location to print to
     */
    fun printToIpp(
        pdfBytes: ByteArray,
        printerLocationId: Long,
    ) {
        viewModelScope.launch {
            _isIppPrinting.value = true
            _ippPrintError.value = null
            logger.i { "Printing PDF to IPP printer location: $printerLocationId" }

            runCatchingApi { printerDiscoveryApiClient.printPdf(pdfBytes, printerLocationId) }
                .onSuccess { resp ->
                    logger.i { "IPP print successful: ${resp.message}" }
                    _ippPrintSuccess.value = "PDF an Drucker gesendet"
                }.onError { uiError ->
                    logger.e { "IPP print failed: $uiError" }
                    _ippPrintError.value = uiError
                }
            _isIppPrinting.value = false
        }
    }

    /**
     * Clears the IPP print success message after display.
     */
    fun clearIppPrintSuccess() {
        _ippPrintSuccess.value = null
    }

    /**
     * Clears the IPP print error after display.
     */
    fun clearIppPrintError() {
        _ippPrintError.value = null
    }
}

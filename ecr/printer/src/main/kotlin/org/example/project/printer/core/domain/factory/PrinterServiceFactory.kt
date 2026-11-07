/**
 * Factory for selecting appropriate printer implementation based on type.
 *
 * Provides dynamic printer service selection at runtime based on
 * printer configuration (USB, Network, or IPP).
 *
 * @property usbPrintService USB printer service implementation
 * @property networkPrintService Network printer service implementation
 * @property ippPrintService IPP printer service implementation
 *
 * @since 1.0.0
 */
package org.example.project.printer.core.domain.factory

import org.example.project.core.domain.enums.setup.PrinterType
import org.example.project.printer.core.data.service.NetworkPrintServiceImpl
import org.example.project.printer.core.data.service.UsbPrintServiceImpl
import org.example.project.printer.core.domain.service.PrintServiceInterface
import org.example.project.printer.ipp.data.service.IppPrinterServiceImpl

/**
 * Factory for printer service selection.
 *
 * Returns the appropriate printer implementation (USB, Network, or IPP)
 * based on the printer configuration type.
 */
class PrinterServiceFactory(
    private val usbPrintService: UsbPrintServiceImpl,
    private val networkPrintService: NetworkPrintServiceImpl,
    private val ippPrintService: IppPrinterServiceImpl,
) {
    /**
     * Get the appropriate printer service implementation.
     *
     * Selects between USB, Network, and IPP printer implementations
     * based on the printer type from configuration.
     *
     * @param printerType Printer connection type (USB, NETWORK, or IPP)
     * @return Appropriate PrintServiceInterface implementation
     */
    fun getPrintService(printerType: PrinterType): PrintServiceInterface =
        when (printerType) {
            PrinterType.USB -> usbPrintService
            PrinterType.NETWORK -> networkPrintService
            PrinterType.IPP -> ippPrintService
        }
}

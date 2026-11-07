/**
 * Koin dependency injection module for the printer engine.
 *
 * Provides all print-engine dependencies: USB, Network, and IPP print services,
 * the printer service factory, document formatters, and the print formatter service.
 *
 * This module can be included by the main server or used standalone.
 *
 * @since 1.0.0
 */
package org.example.project.printer.di

import org.example.project.printer.core.data.service.NetworkPrintServiceImpl
import org.example.project.printer.core.data.service.PrintFormatterServiceImpl
import org.example.project.printer.core.data.service.UsbPrintServiceImpl
import org.example.project.printer.core.domain.factory.PrinterServiceFactory
import org.example.project.printer.core.domain.service.PrintFormatterService
import org.example.project.printer.core.formatter.BillFormatter
import org.example.project.printer.core.formatter.OrderFormatter
import org.example.project.printer.core.formatter.ReferencePrintFormatter
import org.example.project.printer.ipp.data.service.IppPrinterServiceImpl
import org.example.project.printer.ipp.domain.service.IppPrinterService
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

/**
 * Koin module providing all printer engine dependencies.
 *
 * Registers:
 * - [UsbPrintServiceImpl] — USB thermal printer service
 * - [NetworkPrintServiceImpl] — Network (TCP socket) printer service
 * - [IppPrinterServiceImpl] — IPP protocol printer service (discovery + printing)
 * - [PrinterServiceFactory] — Factory for selecting printer by type
 * - [BillFormatter], [OrderFormatter], [ReferencePrintFormatter] — Document formatters
 *   (the Z-Bon formatter is fiscal and is bound by the host)
 * - [PrintFormatterServiceImpl] — ESC/POS document formatter (delegates to individual formatters)
 */
val printerEngineModule =
    module {
        // Print service implementations (one per protocol)
        singleOf(::UsbPrintServiceImpl)
        singleOf(::NetworkPrintServiceImpl)
        singleOf(::IppPrinterServiceImpl) { bind<IppPrinterService>() }

        // Factory to select the right print service by PrinterType
        single<PrinterServiceFactory> {
            PrinterServiceFactory(
                get<UsbPrintServiceImpl>(),
                get<NetworkPrintServiceImpl>(),
                get<IppPrinterServiceImpl>(),
            )
        }

        // Document formatters (one per document type)
        singleOf(::BillFormatter)
        singleOf(::OrderFormatter)
        singleOf(::ReferencePrintFormatter)

        // Formatter service — thin delegator to individual formatters
        singleOf(::PrintFormatterServiceImpl) { bind<PrintFormatterService>() }
    }

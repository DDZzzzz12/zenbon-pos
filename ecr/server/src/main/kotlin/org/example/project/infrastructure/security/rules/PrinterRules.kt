package org.example.project.infrastructure.security.rules

import io.ktor.http.HttpMethod
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.infrastructure.security.AccessLevel
import org.example.project.infrastructure.security.AccessLevel.Cap
import org.example.project.infrastructure.security.RuleModule

/**
 * Printer discovery, printer locations and print-job history — `PrinterDiscoveryRoutes.kt`,
 * `PrinterLocationRoutes.kt`, `PrintJobHistoryRoutes.kt`.
 *
 * All `Authenticated` before the migration, including the location writes and test prints, so
 * both capabilities join `TERMINAL_DEVICE` — a till must be able to configure its own printer.
 */
object PrinterRules : RuleModule {
    override val name = "Printers"

    override val pathPrefixes = listOf("/api/printer-discovery", "/api/printer-location", "/api/print-job-history")

    override val allowedCapabilities = setOf(Capability.PRINTER_READ, Capability.PRINTER_WRITE)

    override val rules: Map<Pair<String, HttpMethod>, AccessLevel> =
        mapOf(
            ("/api/printer-discovery/discover" to HttpMethod.Get) to Cap(Capability.PRINTER_READ),
            ("/api/printer-discovery/status" to HttpMethod.Get) to Cap(Capability.PRINTER_READ),
            ("/api/printer-discovery/formats" to HttpMethod.Get) to Cap(Capability.PRINTER_READ),
            ("/api/printer-discovery/print-pdf" to HttpMethod.Post) to Cap(Capability.PRINTER_WRITE),
            ("/api/printer-discovery/test-print/{printerLocationId}" to HttpMethod.Post) to Cap(Capability.PRINTER_WRITE),
            ("/api/printer-location" to HttpMethod.Post) to Cap(Capability.PRINTER_WRITE),
            ("/api/printer-location" to HttpMethod.Get) to Cap(Capability.PRINTER_READ),
            ("/api/printer-location/{id}" to HttpMethod.Put) to Cap(Capability.PRINTER_WRITE),
            ("/api/printer-location/{id}" to HttpMethod.Delete) to Cap(Capability.PRINTER_WRITE),
            ("/api/printer-location/{id}/activate" to HttpMethod.Patch) to Cap(Capability.PRINTER_WRITE),
            ("/api/print-job-history" to HttpMethod.Get) to Cap(Capability.PRINTER_READ),
        )
}

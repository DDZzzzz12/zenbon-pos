/**
 * Exposed table definition for printer location configuration.
 *
 * Defines the database schema structure for printer location setup with proper
 * column types and constraints. Links physical or logical printer locations
 * with specific printer configurations and printing capabilities.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.printer.database.tables

import org.example.project.core.domain.enums.setup.BeepMode
import org.example.project.core.domain.enums.setup.Language
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable

/**
 * Exposed table definition for printer_location table.
 *
 * Manages printer location configuration linking specific locations
 * with printer setups and defining printing capabilities per location.
 * Supports distributed printing in multi-location environments.
 */
object PrinterLocationTable : LongIdTable("printer_location", "id") {
    /** Location identifier/name for the printer */
    val printerLocation = varchar("printer_location", 255).uniqueIndex()

    /** Whether this location can print bills/receipts */
    val printBill = bool("print_bill")

    /** Whether this printer location is active (soft delete) */
    val isActive = bool("is_active").default(true)

    /** Language for print formatting (determines character encoding and localization) */
    val language = enumerationByName("language", 20, Language::class).default(Language.GERMAN)

    /** Beep alert mode for order ticket notifications (DISABLED, SHORT, LONG) */
    val beepMode = enumerationByName("beep_mode", 20, BeepMode::class).default(BeepMode.DISABLED)

    /** Reference to printer setup configuration */
    val printerSetupId = reference("printer_setup_id", PrinterSetupTable).uniqueIndex()
}

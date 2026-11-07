/**
 * XML model classes for DATEV LedgerImport documents.
 *
 * These models map directly to the DATEV XSD at namespace
 * `http://xml.datev.de/bedi/tps/ledger/v060` and are written
 * via [javax.xml.stream.XMLStreamWriter] in the generator classes.
 *
 * Two ledger entry types exist: [CashLedgerEntryXml] serializes as
 * `<cashLedger>` and [ApLedgerEntryXml] serializes as `<accountsPayableLedger>`.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.export.datevxml.model

/**
 * Container object for DATEV LedgerImport XML model classes.
 *
 * These are plain data holders — XML generation is handled by
 * XMLStreamWriter in the generator classes.
 */
object LedgerXml {
    /** DATEV ledger namespace URI */
    const val NS_LEDGER = "http://xml.datev.de/bedi/tps/ledger/v060"

    /** XSI namespace URI for schema location */
    const val NS_XSI = "http://www.w3.org/2001/XMLSchema-instance"

    /** Schema location value for ledger XMLs */
    const val SCHEMA_LOCATION = "http://xml.datev.de/bedi/tps/ledger/v060 Belegverwaltung_online_ledger_import_v060.xsd"

    /** Fixed legal text required by XSD */
    const val XML_DATA = "Kopie nur zur Verbuchung berechtigt nicht zum Vorsteuerabzug"

    /**
     * Root element `<LedgerImport>` with ledger namespace.
     *
     * @property version Schema version (fixed "6.0")
     * @property generatorInfo Company/shop name (sender info)
     * @property generatingSystem Software identifier (e.g., "POS-KMP")
     * @property consolidate Single consolidate block grouping all entries
     */
    data class LedgerImportXml(
        val version: String = "6.0",
        val generatorInfo: String,
        val generatingSystem: String,
        val consolidate: ConsolidateXml,
    )

    /**
     * The `<consolidate>` element grouping all ledger entries for a single day/invoice.
     *
     * @property consolidatedAmount Sum of all entry amounts (2 decimal places)
     * @property consolidatedDate Date for the consolidation (YYYY-MM-DD)
     * @property consolidatedCurrencyCode Currency code (typically "EUR")
     * @property consolidatedInvoiceId Optional shared invoice ID (used for AP exports)
     * @property consolidatedDeliveryDate Optional shared delivery date (used for AP exports)
     * @property entries List of ledger entries (cashLedger or accountsPayableLedger)
     */
    data class ConsolidateXml(
        val consolidatedAmount: String,
        val consolidatedDate: String,
        val consolidatedCurrencyCode: String = "EUR",
        val consolidatedInvoiceId: String? = null,
        val consolidatedDeliveryDate: String? = null,
        val entries: List<LedgerEntryXml>,
    )

    /**
     * Base interface for ledger entry types within a consolidate block.
     */
    sealed interface LedgerEntryXml

    /**
     * A `<cashLedger>` entry within a consolidate block.
     *
     * All child fields are XML elements (not attributes) per the DATEV XSD.
     * Element order matches the XSD `<xsd:sequence>`.
     */
    data class CashLedgerEntryXml(
        val date: String,
        val amount: String,
        val accountNo: String? = null,
        val buCode: String? = null,
        val costCategoryId: String? = null,
        val costCategoryId2: String? = null,
        val tax: String? = null,
        val information: String? = null,
        val currencyCode: String,
        val invoiceId: String? = null,
        val bookingText: String,
    ) : LedgerEntryXml

    /**
     * An `<accountsPayableLedger>` entry within a consolidate block.
     *
     * All child fields are XML elements per the DATEV XSD.
     * Element order matches the XSD `<xsd:sequence>`: base -> base1 -> accountsPayableLedger.
     */
    data class ApLedgerEntryXml(
        // base fields (XSD sequence order)
        val date: String,
        val amount: String,
        val accountNo: String? = null,
        val buCode: String? = null,
        val costCategoryId: String? = null,
        val costCategoryId2: String? = null,
        val tax: String? = null,
        val information: String? = null,
        // base1 fields (XSD sequence order)
        val currencyCode: String,
        val invoiceId: String,
        val bookingText: String? = null,
        val shipFromCountry: String? = null,
        val partyId: String? = null,
        val paidAt: String? = null,
        val vatId: String? = null,
        val shipToCountry: String? = null,
        val iban: String? = null,
        val dueDate: String? = null,
        val bpAccountNo: String? = null,
        val deliveryDate: String? = null,
        val orderId: String? = null,
        // accountsPayableLedger-specific fields
        val supplierName: String? = null,
        val supplierCity: String? = null,
    ) : LedgerEntryXml
}

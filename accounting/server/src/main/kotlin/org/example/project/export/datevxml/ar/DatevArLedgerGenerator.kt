/**
 * Generates per-invoice ledger XML files for DATEV XML Ausgangsrechnungen export.
 *
 * Each ledger file uses the namespace `http://xml.datev.de/bedi/tps/ledger/v060`
 * with a `<LedgerImport>` root element containing a single `<consolidate>` block.
 * Inside the consolidate block, each tax-rate split is an `<accountsReceivableLedger>` element.
 *
 * Supports split-tax invoices: when an invoice has items at multiple VAT rates,
 * multiple `<accountsReceivableLedger>` entries share the same `consolidatedInvoiceId`.
 *
 * Mirrors [org.example.project.export.datevxml.ap.DatevApLedgerGenerator] — the only
 * differences are the entry element name (`accountsReceivableLedger` instead of
 * `accountsPayableLedger`) and the party block (buyer instead of supplier). The
 * shared `LedgerXml` model is reused as-is (entries are carried as
 * [org.example.project.export.datevxml.model.LedgerXml.ApLedgerEntryXml] instances,
 * with buyer values placed into the `supplierName`/`supplierCity` fields — only the
 * XML attribute name written by this generator matters, not the Kotlin property name).
 *
 * Uses [javax.xml.stream.XMLStreamWriter] for exact control over namespace
 * declarations and element structure — ensuring XSD compliance.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.export.datevxml.ar

import org.example.project.core.domain.dto.datevxml.DatevArExportDto
import org.example.project.export.datevxml.DatevXmlFieldValidator
import org.example.project.export.datevxml.model.LedgerXml
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.math.RoundingMode
import javax.xml.stream.XMLOutputFactory
import javax.xml.stream.XMLStreamWriter

/**
 * Generator for per-invoice DATEV XML ledger files (Rechnungsdaten_AR_{invoiceId}.xml).
 */
object DatevArLedgerGenerator {
    /**
     * Generates a single per-invoice ledger XML file with multiple AR entries.
     *
     * The consolidate block groups entries by invoice, using consolidatedInvoiceId
     * and consolidatedDate as key identifiers. consolidatedAmount is the sum of
     * all entry amounts.
     *
     * @param entries One or more accounts receivable ledger entries for this invoice
     *                (one per tax-rate split, or a single entry for single-tax invoices)
     * @param invoiceId Shared invoice number for the consolidate block
     * @param invoiceDate Shared invoice date (YYYY-MM-DD) for the consolidate block
     * @param generatorInfo Shop/company name (displayed as sender info)
     * @param generatingSystem Software name (e.g., "POS-KMP")
     * @param deliveryDate Optional shared delivery date for the consolidate block
     * @return Ledger XML content as UTF-8 ByteArray
     */
    fun generate(
        entries: List<DatevArExportDto.ArLedgerEntryDto>,
        invoiceId: String,
        invoiceDate: String,
        generatorInfo: String,
        generatingSystem: String,
        deliveryDate: String? = null,
    ): ByteArray {
        // Calculate consolidated amount as sum of all entry amounts
        val consolidatedAmount =
            entries.fold(BigDecimal.ZERO) { acc, entry ->
                acc.add(DatevXmlFieldValidator.requireValidAmount(entry.amount, "AR entry invoiceId=$invoiceId"))
            }

        // Use currency from the first entry (all entries in a consolidate share the same currency)
        val currencyCode = entries.firstOrNull()?.currencyCode ?: "EUR"

        // Map DTOs to XML model objects. Buyer fields ride in the shared model's
        // supplierName/supplierCity slots — see the class-level note on why this
        // is a deliberate reuse rather than a fork of the model.
        val xmlEntries =
            entries.map { entry ->
                LedgerXml.ApLedgerEntryXml(
                    date = entry.date,
                    amount = entry.amount,
                    accountNo = entry.accountNo,
                    buCode = entry.buCode,
                    bpAccountNo = entry.bpAccountNo,
                    tax = entry.tax,
                    information = entry.information,
                    currencyCode = entry.currencyCode,
                    invoiceId = entry.invoiceId,
                    bookingText = entry.bookingText,
                    partyId = entry.partyId,
                    supplierName = entry.buyerName,
                    supplierCity = entry.buyerCity,
                    vatId = entry.vatId,
                    dueDate = entry.dueDate,
                    deliveryDate = entry.deliveryDate,
                )
            }

        val model =
            LedgerXml.LedgerImportXml(
                generatorInfo = generatorInfo,
                generatingSystem = generatingSystem,
                consolidate =
                    LedgerXml.ConsolidateXml(
                        consolidatedAmount = formatDecimal(consolidatedAmount),
                        consolidatedDate = invoiceDate,
                        consolidatedCurrencyCode = currencyCode,
                        consolidatedInvoiceId = invoiceId,
                        consolidatedDeliveryDate = deliveryDate,
                        entries = xmlEntries,
                    ),
            )

        return writeLedgerImportXml(model)
    }

    /**
     * Writes a [LedgerXml.LedgerImportXml] model to XML bytes using XMLStreamWriter.
     *
     * @param model The ledger import model to serialize
     * @return UTF-8 encoded XML bytes
     */
    private fun writeLedgerImportXml(model: LedgerXml.LedgerImportXml): ByteArray {
        val baos = ByteArrayOutputStream()
        val writer = XMLOutputFactory.newInstance().createXMLStreamWriter(baos, "UTF-8")

        writer.writeStartDocument("UTF-8", "1.0")
        writer.writeCharacters("\n")

        // <LedgerImport xmlns="..." xmlns:xsi="..." xsi:schemaLocation="..." ...>
        writer.writeStartElement("LedgerImport")
        writer.writeDefaultNamespace(LedgerXml.NS_LEDGER)
        writer.writeNamespace("xsi", LedgerXml.NS_XSI)
        writer.writeAttribute(LedgerXml.NS_XSI, "schemaLocation", LedgerXml.SCHEMA_LOCATION)
        writer.writeAttribute("version", model.version)
        writer.writeAttribute("generator_info", model.generatorInfo)
        writer.writeAttribute("generating_system", model.generatingSystem)
        writer.writeAttribute("xml_data", LedgerXml.XML_DATA)
        writer.writeCharacters("\n")

        // <consolidate>
        writeConsolidate(writer, model.consolidate)

        // </LedgerImport>
        writer.writeEndElement()
        writer.writeCharacters("\n")
        writer.writeEndDocument()
        writer.flush()
        writer.close()

        return baos.toByteArray()
    }

    /**
     * Writes the `<consolidate>` element with all AR ledger entries.
     */
    private fun writeConsolidate(
        writer: XMLStreamWriter,
        consolidate: LedgerXml.ConsolidateXml,
    ) {
        writer.writeCharacters("  ")
        writer.writeStartElement("consolidate")
        writer.writeAttribute("consolidatedAmount", consolidate.consolidatedAmount)
        writer.writeAttribute("consolidatedDate", consolidate.consolidatedDate)
        consolidate.consolidatedInvoiceId?.let { writer.writeAttribute("consolidatedInvoiceId", it) }
        writer.writeAttribute("consolidatedCurrencyCode", consolidate.consolidatedCurrencyCode)
        consolidate.consolidatedDeliveryDate?.let { writer.writeAttribute("consolidatedDeliveryDate", it) }
        writer.writeCharacters("\n")

        for (entry in consolidate.entries) {
            writeArLedgerEntry(writer, entry as LedgerXml.ApLedgerEntryXml)
        }

        writer.writeCharacters("  ")
        writer.writeEndElement() // </consolidate>
        writer.writeCharacters("\n")
    }

    /**
     * Writes a single `<accountsReceivableLedger>` entry with child elements in XSD sequence order.
     *
     * Element order mirrors the AP writer (base -> base1 -> party block), but drops the
     * AP-only fields that have no counterpart on [DatevArExportDto.ArLedgerEntryDto]
     * (costCategoryId/costCategoryId2, shipFromCountry, paidAt, shipToCountry, iban, orderId).
     * Optional elements are omitted when null.
     */
    private fun writeArLedgerEntry(
        writer: XMLStreamWriter,
        entry: LedgerXml.ApLedgerEntryXml,
    ) {
        writer.writeCharacters("    ")
        writer.writeStartElement("accountsReceivableLedger")
        writer.writeCharacters("\n")

        // === base type fields ===
        writeTextElement(writer, "date", entry.date)
        writeTextElement(writer, "amount", entry.amount)
        entry.accountNo?.let { writeTextElement(writer, "accountNo", it) }
        entry.buCode?.let { writeTextElement(writer, "buCode", it) }
        entry.tax?.let { writeTextElement(writer, "tax", it) }
        entry.information?.let { writeTextElement(writer, "information", it) }

        // === base1 type fields ===
        writeTextElement(writer, "currencyCode", entry.currencyCode)
        writeTextElement(writer, "invoiceId", entry.invoiceId)
        entry.bookingText?.let { writeTextElement(writer, "bookingText", it) }
        entry.partyId?.let { writeTextElement(writer, "partyId", it) }
        entry.vatId?.let { writeTextElement(writer, "vatId", it) }
        entry.dueDate?.let { writeTextElement(writer, "dueDate", it) }
        entry.bpAccountNo?.let { writeTextElement(writer, "bpAccountNo", it) }
        entry.deliveryDate?.let { writeTextElement(writer, "deliveryDate", it) }

        // === accountsReceivableLedger type fields (customer block; XSD requires customerName/customerCity) ===
        entry.supplierName?.let { writeTextElement(writer, "customerName", it) }
        entry.supplierCity?.let { writeTextElement(writer, "customerCity", it) }

        writer.writeCharacters("    ")
        writer.writeEndElement() // </accountsReceivableLedger>
        writer.writeCharacters("\n")
    }

    /**
     * Writes a simple text element like `<name>value</name>` with 6-space indent.
     */
    private fun writeTextElement(
        writer: XMLStreamWriter,
        name: String,
        value: String,
    ) {
        writer.writeCharacters("      ")
        writer.writeStartElement(name)
        writer.writeCharacters(value)
        writer.writeEndElement()
        writer.writeCharacters("\n")
    }

    /**
     * Formats a BigDecimal to DATEV-compatible decimal string with 2 decimal places.
     */
    private fun formatDecimal(value: BigDecimal): String = value.setScale(2, RoundingMode.HALF_UP).toPlainString()
}

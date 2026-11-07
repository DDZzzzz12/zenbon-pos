/**
 * Generates per-invoice ledger XML files for DATEV XML Eingangsrechnungen export.
 *
 * Each ledger file uses the namespace `http://xml.datev.de/bedi/tps/ledger/v060`
 * with a `<LedgerImport>` root element containing a single `<consolidate>` block.
 * Inside the consolidate block, each tax-rate split is an `<accountsPayableLedger>` element.
 *
 * Supports split-tax invoices: when an invoice has items at multiple VAT rates,
 * multiple `<accountsPayableLedger>` entries share the same `consolidatedInvoiceId`.
 *
 * Uses [javax.xml.stream.XMLStreamWriter] for exact control over namespace
 * declarations and element structure — ensuring XSD compliance.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.export.datevxml.ap

import org.example.project.core.domain.dto.datevxml.DatevApExportDto
import org.example.project.export.datevxml.DatevXmlFieldValidator
import org.example.project.export.datevxml.DatevXmlLedgerGenerator
import org.example.project.export.datevxml.model.LedgerXml
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.math.RoundingMode
import javax.xml.stream.XMLOutputFactory
import javax.xml.stream.XMLStreamWriter

/**
 * Generator for per-invoice DATEV XML ledger files (Rechnungsdaten_RE_{invoiceId}.xml).
 */
object DatevApLedgerGenerator {
    /**
     * Generates a single per-invoice ledger XML file with multiple AP entries.
     *
     * The consolidate block groups entries by invoice, using consolidatedInvoiceId
     * and consolidatedDate as key identifiers. consolidatedAmount is the sum of
     * all entry amounts.
     *
     * @param entries One or more accounts payable ledger entries for this invoice
     *                (one per tax-rate split, or a single entry for single-tax invoices)
     * @param invoiceId Shared invoice number for the consolidate block
     * @param invoiceDate Shared invoice date (YYYY-MM-DD) for the consolidate block
     * @param generatorInfo Shop/company name (displayed as sender info)
     * @param generatingSystem Software name (e.g., "POS-KMP")
     * @param deliveryDate Optional shared delivery date for the consolidate block
     * @return Ledger XML content as UTF-8 ByteArray
     */
    fun generate(
        entries: List<DatevApExportDto.ApLedgerEntryDto>,
        invoiceId: String,
        invoiceDate: String,
        generatorInfo: String,
        generatingSystem: String,
        deliveryDate: String? = null,
    ): ByteArray {
        // Calculate consolidated amount as sum of all entry amounts
        val consolidatedAmount =
            entries.fold(BigDecimal.ZERO) { acc, entry ->
                acc.add(DatevXmlFieldValidator.requireValidAmount(entry.amount, "AP entry invoiceId=$invoiceId"))
            }

        // Use currency from the first entry (all entries in a consolidate share the same currency)
        val currencyCode = entries.firstOrNull()?.currencyCode ?: "EUR"

        // Map DTOs to XML model objects
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
                    costCategoryId = entry.costCategoryId,
                    costCategoryId2 = entry.costCategoryId2,
                    currencyCode = entry.currencyCode,
                    invoiceId = entry.invoiceId,
                    bookingText = entry.bookingText,
                    partyId = entry.partyId,
                    supplierName = entry.supplierName,
                    supplierCity = entry.supplierCity,
                    vatId = entry.vatId,
                    iban = entry.iban,
                    dueDate = entry.dueDate,
                    deliveryDate = entry.deliveryDate,
                    paidAt = entry.paidAt,
                    shipFromCountry = entry.shipFromCountry,
                    shipToCountry = entry.shipToCountry,
                    orderId = entry.orderId,
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
     * Writes the `<consolidate>` element with all AP ledger entries.
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
            // Reuse the shared AP entry writer from DatevXmlLedgerGenerator
            DatevXmlLedgerGenerator.writeApLedgerEntry(writer, entry as LedgerXml.ApLedgerEntryXml)
        }

        writer.writeCharacters("  ")
        writer.writeEndElement() // </consolidate>
        writer.writeCharacters("\n")
    }

    /**
     * Formats a BigDecimal to DATEV-compatible decimal string with 2 decimal places.
     */
    private fun formatDecimal(value: BigDecimal): String = value.setScale(2, RoundingMode.HALF_UP).toPlainString()
}

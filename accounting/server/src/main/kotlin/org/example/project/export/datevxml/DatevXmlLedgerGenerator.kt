/**
 * Generates per-day ledger XML files for DATEV XML Schnittstelle online.
 *
 * Each ledger file uses the namespace `http://xml.datev.de/bedi/tps/ledger/v060`
 * with a `<LedgerImport>` root element containing a single `<consolidate>` block.
 * Inside the consolidate block, each Kassenbuch entry is a `<cashLedger>` element.
 *
 * Per the XSD, the `<LedgerImport>` element requires:
 * - version="6.0"
 * - generator_info (company/shop name)
 * - generating_system (software name)
 * - xml_data (fixed legal text: "Kopie nur zur Verbuchung berechtigt nicht zum Vorsteuerabzug")
 *
 * The `<consolidate>` element requires:
 * - consolidatedAmount (sum of all amounts)
 * - consolidatedDate (the date for all entries — same day)
 * - consolidatedCurrencyCode="EUR"
 *
 * Uses [javax.xml.stream.XMLStreamWriter] for exact control over namespace
 * declarations and element structure — ensuring XSD compliance.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.export.datevxml

import org.example.project.core.domain.dto.datevxml.DatevXmlExportDto
import org.example.project.export.datevxml.model.LedgerXml
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.math.RoundingMode
import javax.xml.stream.XMLOutputFactory
import javax.xml.stream.XMLStreamWriter

/**
 * Generator for per-day DATEV XML ledger files (Kasse_YYYYMMDD.xml).
 */
object DatevXmlLedgerGenerator {
    /**
     * Generates a single per-day ledger XML file.
     *
     * All entries must share the same date (consolidatedDate requirement).
     * The consolidatedAmount must equal the sum of all entry amounts.
     *
     * Output matches the DATEV reference format:
     * ```xml
     * <?xml version="1.0" encoding="UTF-8"?>
     * <LedgerImport xmlns="http://xml.datev.de/bedi/tps/ledger/v060"
     *               xmlns:xsi="..." xsi:schemaLocation="..."
     *               version="6.0" generator_info="..." generating_system="..."
     *               xml_data="...">
     *   <consolidate consolidatedAmount="..." consolidatedDate="..." consolidatedCurrencyCode="EUR">
     *     <cashLedger>
     *       <date>...</date>
     *       <amount>...</amount>
     *       ...
     *     </cashLedger>
     *   </consolidate>
     * </LedgerImport>
     * ```
     *
     * @param entries List of cash ledger entries for a single day
     * @param date The date for this file in YYYY-MM-DD format
     * @param generatorInfo Shop/company name (displayed as sender info)
     * @param generatingSystem Software name (e.g., "POS-KMP")
     * @return Ledger XML content as UTF-8 ByteArray
     */
    fun generate(
        entries: List<DatevXmlExportDto.CashLedgerEntryDto>,
        date: String,
        generatorInfo: String,
        generatingSystem: String,
    ): ByteArray {
        // Calculate consolidated amount (sum of all entries)
        val consolidatedAmount =
            entries.fold(BigDecimal.ZERO) { acc, entry ->
                acc + DatevXmlFieldValidator.requireValidAmount(entry.amount, "cashLedger entry date=${entry.date}")
            }

        // Map DTOs to XML model objects
        val xmlEntries =
            entries.map { entry ->
                LedgerXml.CashLedgerEntryXml(
                    date = entry.date,
                    amount = entry.amount,
                    accountNo = entry.accountNo,
                    buCode = entry.buCode,
                    costCategoryId = entry.costCategoryId,
                    costCategoryId2 = entry.costCategoryId2,
                    tax = entry.tax,
                    information = entry.information,
                    currencyCode = entry.currencyCode,
                    invoiceId = entry.invoiceId,
                    bookingText = entry.bookingText,
                )
            }

        val model =
            LedgerXml.LedgerImportXml(
                generatorInfo = generatorInfo,
                generatingSystem = generatingSystem,
                consolidate =
                    LedgerXml.ConsolidateXml(
                        consolidatedAmount = formatDecimal(consolidatedAmount),
                        consolidatedDate = date,
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

        // <LedgerImport xmlns="..." xmlns:xsi="..." xsi:schemaLocation="..." version="..." ...>
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
     * Writes the `<consolidate>` element with all cashLedger entries.
     */
    private fun writeConsolidate(
        writer: XMLStreamWriter,
        consolidate: LedgerXml.ConsolidateXml,
    ) {
        writer.writeCharacters("  ")
        writer.writeStartElement("consolidate")
        writer.writeAttribute("consolidatedAmount", consolidate.consolidatedAmount)
        writer.writeAttribute("consolidatedDate", consolidate.consolidatedDate)
        writer.writeAttribute("consolidatedCurrencyCode", consolidate.consolidatedCurrencyCode)
        consolidate.consolidatedInvoiceId?.let { writer.writeAttribute("consolidatedInvoiceId", it) }
        consolidate.consolidatedDeliveryDate?.let { writer.writeAttribute("consolidatedDeliveryDate", it) }
        writer.writeCharacters("\n")

        for (entry in consolidate.entries) {
            writeLedgerEntry(writer, entry)
        }

        writer.writeCharacters("  ")
        writer.writeEndElement() // </consolidate>
        writer.writeCharacters("\n")
    }

    /**
     * Writes a single ledger entry element (`<cashLedger>` or `<accountsPayableLedger>`).
     */
    private fun writeLedgerEntry(
        writer: XMLStreamWriter,
        entry: LedgerXml.LedgerEntryXml,
    ) {
        when (entry) {
            is LedgerXml.CashLedgerEntryXml -> writeCashLedgerEntry(writer, entry)
            is LedgerXml.ApLedgerEntryXml -> writeApLedgerEntry(writer, entry)
        }
    }

    /**
     * Writes a `<cashLedger>` entry with child elements in XSD sequence order.
     *
     * Optional elements are omitted when null (per reference samples).
     */
    private fun writeCashLedgerEntry(
        writer: XMLStreamWriter,
        entry: LedgerXml.CashLedgerEntryXml,
    ) {
        writer.writeCharacters("    ")
        writer.writeStartElement("cashLedger")
        writer.writeCharacters("\n")

        // XSD sequence order: date, amount, accountNo, buCode, costCategoryId,
        // costCategoryId2, tax, information, currencyCode, invoiceId, bookingText
        writeTextElement(writer, "date", entry.date)
        writeTextElement(writer, "amount", entry.amount)
        entry.accountNo?.let { writeTextElement(writer, "accountNo", it) }
        entry.buCode?.let { writeTextElement(writer, "buCode", it) }
        entry.costCategoryId?.let { writeTextElement(writer, "costCategoryId", it) }
        entry.costCategoryId2?.let { writeTextElement(writer, "costCategoryId2", it) }
        entry.tax?.let { writeTextElement(writer, "tax", it) }
        entry.information?.let { writeTextElement(writer, "information", it) }
        writeTextElement(writer, "currencyCode", entry.currencyCode)
        entry.invoiceId?.let { writeTextElement(writer, "invoiceId", it) }
        writeTextElement(writer, "bookingText", entry.bookingText)

        writer.writeCharacters("    ")
        writer.writeEndElement() // </cashLedger>
        writer.writeCharacters("\n")
    }

    /**
     * Writes an `<accountsPayableLedger>` entry with child elements in XSD sequence order.
     *
     * Element order follows XSD: base -> base1 -> accountsPayableLedger.
     * Optional elements are omitted when null.
     */
    internal fun writeApLedgerEntry(
        writer: XMLStreamWriter,
        entry: LedgerXml.ApLedgerEntryXml,
    ) {
        writer.writeCharacters("    ")
        writer.writeStartElement("accountsPayableLedger")
        writer.writeCharacters("\n")

        // === base type fields ===
        writeTextElement(writer, "date", entry.date)
        writeTextElement(writer, "amount", entry.amount)
        // discountAmount would go here (not yet supported)
        entry.accountNo?.let { writeTextElement(writer, "accountNo", it) }
        entry.buCode?.let { writeTextElement(writer, "buCode", it) }
        // costAmount would go here (not yet supported)
        entry.costCategoryId?.let { writeTextElement(writer, "costCategoryId", it) }
        entry.costCategoryId2?.let { writeTextElement(writer, "costCategoryId2", it) }
        entry.tax?.let { writeTextElement(writer, "tax", it) }
        entry.information?.let { writeTextElement(writer, "information", it) }

        // === base1 type fields ===
        writeTextElement(writer, "currencyCode", entry.currencyCode)
        writeTextElement(writer, "invoiceId", entry.invoiceId)
        entry.bookingText?.let { writeTextElement(writer, "bookingText", it) }
        // typeOfReceivable would go here (not yet supported)
        // ownVatId would go here (not yet supported)
        entry.shipFromCountry?.let { writeTextElement(writer, "shipFromCountry", it) }
        entry.partyId?.let { writeTextElement(writer, "partyId", it) }
        entry.paidAt?.let { writeTextElement(writer, "paidAt", it) }
        // internalInvoiceId would go here (not yet supported)
        entry.vatId?.let { writeTextElement(writer, "vatId", it) }
        entry.shipToCountry?.let { writeTextElement(writer, "shipToCountry", it) }
        // exchangeRate would go here (not yet supported)
        // bankCode, bankAccount, bankCountry would go here (not yet supported)
        entry.iban?.let { writeTextElement(writer, "iban", it) }
        // swiftCode, accountName would go here (not yet supported)
        // paymentConditionsId, paymentOrder would go here (not yet supported)
        // discountPercentage, discountPaymentDate would go here (not yet supported)
        // discountAmount2, discountPercentage2, discountPaymentDate2 would go here (not yet supported)
        entry.dueDate?.let { writeTextElement(writer, "dueDate", it) }
        entry.bpAccountNo?.let { writeTextElement(writer, "bpAccountNo", it) }
        entry.deliveryDate?.let { writeTextElement(writer, "deliveryDate", it) }
        entry.orderId?.let { writeTextElement(writer, "orderId", it) }

        // === accountsPayableLedger type fields ===
        entry.supplierName?.let { writeTextElement(writer, "supplierName", it) }
        entry.supplierCity?.let { writeTextElement(writer, "supplierCity", it) }

        writer.writeCharacters("    ")
        writer.writeEndElement() // </accountsPayableLedger>
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
     * Formats a BigDecimal to DATEV-compatible decimal string.
     *
     * Uses dot as decimal separator with 2 decimal places.
     *
     * @param value BigDecimal to format
     * @return Formatted decimal string (e.g., "1234.56")
     */
    private fun formatDecimal(value: BigDecimal): String = value.setScale(2, RoundingMode.HALF_UP).toPlainString()
}

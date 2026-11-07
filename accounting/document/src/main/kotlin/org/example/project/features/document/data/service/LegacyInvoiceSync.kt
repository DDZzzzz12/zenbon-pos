package org.example.project.features.document.data.service

import kotlinx.serialization.json.Json
import org.example.project.core.domain.dto.document.DocumentDto
import org.example.project.features.document.domain.models.DocumentInvoiceMetadata
import org.example.project.features.document.domain.models.Invoice
import org.example.project.features.document.domain.repository.DocumentRepository
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Creates a normalized invoice from a legacy `document_invoice_metadata` row.
 *
 * **This is history-only machinery.** Its sole caller is [InvoiceBackfill], which
 * `SchemaManager.backfillLegacyInvoices()` runs at startup to give pre-migration legacy rows an
 * invoice on the normalized model. Nothing writes `document_invoice_metadata` any more — the legacy
 * write path was frozen when the operator's editor moved onto the normalized aggregate — so after
 * the first successful boot on this build the backfill, and therefore this class, is a no-op.
 *
 * It is kept rather than deleted because the backfill is still the **only** populator of
 * `InvoiceTable` for pre-migration rows, and document search depends on that, not only the DATEV
 * Eingangsrechnungen export: `DocumentRepositoryImpl.invoiceMatches` resolves the vendor,
 * invoice-number and amount filters through a correlated `EXISTS` on `InvoiceTable`. A document
 * whose invoice was never backfilled is invisible to all three filters.
 *
 * ### Create-only, by construction
 * There is one entry point and it creates. [InvoiceBackfill] keeps its own skip-if-exists check, so
 * calling this for a document that already has an invoice would produce a duplicate —
 * `source_document_id` is a plain index, not unique. There is deliberately no update leg: the
 * normalized model is edited through `DocumentManagementService.updateInvoiceSupplierContent` and
 * `updateInvoiceBooking`, which is where the provenance guard lives (a supplier-field write on an
 * `EINVOICE_XML` invoice is rejected with 409, not silently skipped).
 *
 * ### What is carried, and what is not
 * `tax_line_items_json` **is** read; see [vatRows]. It is the one blob the DATEV Eingangsrechnungen
 * export depends on. That export emits **one `<accountsPayableLedger>` entry per VAT breakdown
 * row**, and before this model existed it produced that split by parsing this blob directly. A
 * mixed-VAT supplier invoice — 7 % food alongside 19 % drinks on one bill — therefore has to become
 * one breakdown row *per rate*: collapsing it to a single row loses the per-rate Vorsteuer split
 * the accountant books from, and the header `tax_rate` is null for exactly those invoices (it is
 * nulled when the lines disagree), so the entry would come out with no `<tax>` at all.
 *
 * The other four blob columns (`line_items_json`, `payment_details_json`, `discount_terms_json`,
 * `additional_info_json`) are **not** read — the invoice lines they would feed are written by
 * `ParsedInvoiceWriter`, not reconstructed from a blob, and nothing downstream reads them. Legacy
 * scalars with no destination in the new model (`order_id`, `ship_to_country`, `ship_from_country`,
 * `exchange_rate`, `eu_tax_rate`, `delivery_date_start`, `delivery_date_end`) are left behind on
 * purpose; the legacy table is untouched, so nothing is lost. (`invoice_type` and `drawee_no` are
 * not in that list: they became BT-3 and BT-25 on the normalized model.)
 *
 * A note for anyone reading [splitGross]: `adminApp`'s `capture/VatDerivation.kt` is a
 * transliteration of it and `VatDerivationTest` pins the two against each other. Changing it here
 * silently changes what the capture form derives.
 *
 * Requires an ambient transaction — it reads and writes entities directly, like the repository it
 * calls.
 */
class LegacyInvoiceSync(
    private val repository: DocumentRepository,
) {
    private companion object {
        val HUNDRED: BigDecimal = BigDecimal("100")
        const val MONEY_SCALE = 2
    }

    private val logger = LoggerFactory.getLogger(LegacyInvoiceSync::class.java)

    /**
     * Creates the invoice for a legacy row whose document is known to have none.
     *
     * Used by [InvoiceBackfill] only. Calling it for a document that already has an invoice
     * produces a duplicate — `source_document_id` is a plain index, not unique.
     */
    fun createFromLegacy(legacy: DocumentInvoiceMetadata): Invoice {
        val invoice = writeHeader(legacy)
        writeVatBreakdown(legacy, invoice.id.value)
        writeBooking(legacy, invoice.id.value)
        return invoice
    }

    /**
     * Supplier-asserted content only — our bookkeeping decisions go through [writeBooking].
     *
     * `MANUAL` is the honest provenance: every value below came from a row a human typed into the
     * legacy editor. The parser's counterpart is `EINVOICE_XML`, written by `ParsedInvoiceWriter`,
     * and the backfill never touches a document that already has one.
     */
    private fun writeHeader(legacy: DocumentInvoiceMetadata): Invoice =
        repository.createInvoice(
            sourceDocumentId = legacy.document.id.value,
            extractionSource = "MANUAL",
            invoiceNumber = legacy.invoiceNumber,
            issueDate = legacy.invoiceDate,
            dueDate = legacy.dueDate,
            deliveryDate = legacy.deliveryDate,
            currency = legacy.currency.takeIf { it.isNotBlank() } ?: "EUR",
            sellerName = legacy.vendorName,
            sellerVatId = legacy.vatId,
            sellerStreet = legacy.supplierStreet,
            sellerZip = legacy.supplierZip,
            sellerCity = legacy.supplierCity,
            sellerCountry = legacy.supplierCountry,
            sellerPhone = legacy.supplierPhone,
            sellerEmail = legacy.supplierEmail,
            paymentIban = legacy.iban,
            paymentReference = legacy.paymentReference,
            paymentTermsText = legacy.paymentConditionsText,
            taxExclusiveTotal = legacy.netAmount,
            taxTotal = legacy.taxAmount,
            taxInclusiveTotal = legacy.grossAmount,
        )

    /**
     * Writes the VAT breakdown derived from the legacy row; see [vatRows] for where it comes from.
     *
     * An empty result is skipped rather than written, so backfilling a rate-less legacy row leaves
     * the invoice's breakdown absent rather than explicitly empty.
     */
    private fun writeVatBreakdown(
        legacy: DocumentInvoiceMetadata,
        invoiceId: Long,
    ) {
        val rows = vatRows(legacy)
        if (rows.isEmpty()) return
        repository.replaceVatBreakdown(invoiceId, rows)
    }

    /**
     * The BG-23 rows the DATEV AP export turns into ledger entries, in order of preference:
     * the per-rate split parsed from `tax_line_items_json`, else a single row synthesized from the
     * header `tax_rate`, else nothing.
     *
     * The blob wins because it is the finer-grained truth and the only source of a mixed-rate
     * split — see the class KDoc.
     */
    private fun vatRows(legacy: DocumentInvoiceMetadata) = taxLineRows(legacy) ?: headerRateRow(legacy)

    /**
     * One row per legacy tax line, reproducing exactly what the pre-model export read from the
     * blob: the line's `grossAmount` became the entry amount, its `taxRate` the entry `<tax>`, and
     * its `description` overrode the header booking text. That last one is why `description` is
     * carried onto the row — it drives the per-line DATEV booking-text override.
     *
     * The net is **not** taken from the line's own `netAmount`: the export derives the entry amount
     * as `taxable + vat`, so the pair has to add back up to the line's gross bit-for-bit. It is
     * derived instead and the VAT taken **by subtraction**, as in [resolveVatAmounts].
     *
     * An unparseable value degrades to zero rather than dropping the line, matching the old
     * export's `?: BigDecimal.ZERO`. Returns null — not an empty list — when there is no usable
     * blob at all, so the caller can fall back to the header rate.
     */
    private fun taxLineRows(legacy: DocumentInvoiceMetadata): List<DocumentRepository.VatBreakdownRow>? {
        val json = legacy.taxLineItemsJson ?: return null
        val lines =
            try {
                Json.decodeFromString<List<DocumentDto.TaxLineItemDto>>(json).takeIf { it.isNotEmpty() }
            } catch (e: Exception) {
                logger.warn(
                    "Unparseable tax_line_items_json on document {} — falling back to the header tax rate, " +
                        "so a mixed-rate invoice will export as a single ledger entry",
                    legacy.document.id.value,
                    e,
                )
                null
            } ?: return null

        return lines.map { line ->
            val gross = line.grossAmount.toBigDecimalOrNull() ?: BigDecimal.ZERO
            val rate = line.taxRate.toBigDecimalOrNull() ?: BigDecimal.ZERO
            val amounts = splitGross(gross, rate)

            DocumentRepository.VatBreakdownRow(
                vatCategoryCode = categoryCodeFor(rate),
                vatRate = rate,
                taxableAmount = amounts.first,
                vatAmount = amounts.second,
                description = line.description.takeIf { it.isNotBlank() },
            )
        }
    }

    /**
     * Turns the legacy header `tax_rate` into a single BG-23 row.
     *
     * Nothing here is invented but the category code: the rate and, where present, the net and tax
     * amounts are copied straight across. When only `gross_amount` is known the net is derived and
     * the VAT then taken **by subtraction**, so `taxable + vat == gross` holds exactly — computing
     * the VAT independently would break that invariant on the receipt.
     *
     * A null rate means no VAT was recorded at all, so no row is produced — the invoice keeps the
     * empty-breakdown fallback.
     */
    private fun headerRateRow(legacy: DocumentInvoiceMetadata): List<DocumentRepository.VatBreakdownRow> {
        val rate = legacy.taxRate ?: return emptyList()
        val amounts = resolveVatAmounts(legacy, rate) ?: return emptyList()

        return listOf(
            DocumentRepository.VatBreakdownRow(
                vatCategoryCode = categoryCodeFor(rate),
                vatRate = rate,
                taxableAmount = amounts.first,
                vatAmount = amounts.second,
            ),
        )
    }

    /**
     * A `0.00` rate is `Z` (zero-rated), not `S`. Legacy has no column distinguishing zero-rated
     * from exempt or reverse-charge, and `S` at 0 % would be a false assertion; `Z` is the honest
     * read of "a rate of zero was recorded".
     */
    private fun categoryCodeFor(rate: BigDecimal): String = if (rate > BigDecimal.ZERO) "S" else "Z"

    /** `(taxableAmount, vatAmount)`, preferring the stored pair and deriving from gross otherwise. */
    private fun resolveVatAmounts(
        legacy: DocumentInvoiceMetadata,
        rate: BigDecimal,
    ): Pair<BigDecimal, BigDecimal>? {
        legacy.netAmount?.let { net ->
            legacy.taxAmount?.let { tax -> return net to tax }
        }

        val gross = legacy.grossAmount ?: return null
        return splitGross(gross, rate)
    }

    /**
     * `(taxableAmount, vatAmount)` for a gross at a rate, with the VAT taken **by subtraction** so
     * `taxable + vat == gross` holds exactly. The export sums the pair back into the entry amount,
     * so any independently computed VAT would move money on the ledger.
     */
    private fun splitGross(
        gross: BigDecimal,
        rate: BigDecimal,
    ): Pair<BigDecimal, BigDecimal> {
        val divisor = BigDecimal.ONE.add(rate.divide(HUNDRED, MONEY_SCALE + 2, RoundingMode.HALF_UP))
        val net = gross.divide(divisor, MONEY_SCALE, RoundingMode.HALF_UP)
        return net to gross.subtract(net)
    }

    /**
     * Only writes when the legacy row actually carries a booking decision — an empty booking row
     * would assert we had booked an invoice we never touched.
     *
     * `payment_dunning_block` is non-null in the legacy schema and defaults to `false`, so `false`
     * is treated as "no decision" rather than as a value worth creating a booking row for.
     */
    private fun writeBooking(
        legacy: DocumentInvoiceMetadata,
        invoiceId: Long,
    ) {
        val dunningBlock = true.takeIf { legacy.paymentDunningBlock }
        val hasBooking =
            listOfNotNull(
                legacy.accountNo,
                legacy.bpAccountNo,
                legacy.buCode,
                legacy.bookingText,
                legacy.costCategoryId,
                legacy.costCategoryId2,
                legacy.costAmount,
                legacy.typeOfReceivable,
                legacy.paidAt,
                dunningBlock,
            ).isNotEmpty()
        if (!hasBooking) return

        repository.upsertInvoiceBooking(
            invoiceId = invoiceId,
            accountNo = legacy.accountNo,
            bpAccountNo = legacy.bpAccountNo,
            buCode = legacy.buCode,
            bookingText = legacy.bookingText,
            costCategoryId = legacy.costCategoryId,
            costCategoryId2 = legacy.costCategoryId2,
            costAmount = legacy.costAmount,
            typeOfReceivable = legacy.typeOfReceivable,
            paymentDunningBlock = dunningBlock,
            paidAt = legacy.paidAt,
        )
    }
}

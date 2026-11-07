package org.example.project.utils.builder

import org.example.project.core.domain.dto.order.OrderItemDto
import org.example.project.core.domain.enums.tse.BonType
import org.example.project.core.domain.enums.tse.ProcessType
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.bill.domain.models.BillVatDetail
import org.example.project.features.payment.domain.models.Payment
import org.example.project.utils.NonTaxableTipCarrier
import java.math.BigDecimal
import java.math.RoundingMode

object ProcessDataBuilder {
    private val logger = org.slf4j.LoggerFactory.getLogger(ProcessDataBuilder::class.java)

    /**
     * The Kassenbeleg-V1 processData for a FORWARD sale [Bill], derived from the bill's own
     * training flag, accumulated VAT buckets and payments. Both the live signing path
     * (BillServiceImpl.createBill) and the PENDING-sweeper (round-3 TSER-9) call this single
     * helper so a swept re-enqueue signs BYTE-IDENTICAL data — a divergent rebuild would sign
     * wrong fiscal data. NOT valid for storno reverse bills (they sign the reversed bill's
     * details + refund payments); those must keep their own build. Requires an ambient transaction because
     * [Bill.vatDetails] and [Bill.payments] are lazy referrers.
     *
     * A TrinkgeldAN employee tip is passed as [build]'s `nonTaxableExtra` rather than read out of
     * the VAT breakdown — it is non-taxable and is not turnover, so it is NOT a `BillVatDetail`
     * row. The signed bytes are unchanged: it lands in the same 5th ("nicht steuerbar") brutto
     * slot the old 0 % row occupied (DSFinV-K Anhang I p. 120). See
     * docs/superpowers/plans/2026-09-16-e2e-findings-fixes.md P1.
     *
     * [NonTaxableTipCarrier] — not an inline `tipRecipient` check — owns that decision, because a
     * bill closed BEFORE the change still stores its tip as a 0 % row. Re-deriving such a bill here
     * (the `TsePendingSweeper` replays stranded PENDING bills through this very method) would
     * otherwise fold the stored row AND add the tip again, signing 26.00 against 23.00 of tender.
     */
    fun buildForBill(bill: Bill): String =
        build(
            processType = ProcessType.KASSENBELEG_V1,
            bonType = if (bill.isTraining) BonType.AV_TRAINING else BonType.BELEG,
            vatDetail = bill.vatDetails.toList(),
            payments = bill.payments.toList(),
            nonTaxableExtra = NonTaxableTipCarrier.outsideVatDetails(bill),
        )

    /**
     * The canonical `AVBelegabbruch` Kassenbeleg-V1 processData: `AVBelegabbruch^0.00_0.00_0.00_0.00_0.00^`.
     *
     * DSFinV-K 2.4 Anhang B p. 46 defines when this Vorgangstyp applies:
     * „Der Vorgangstyp ‚AVBelegabbruch' kennzeichnet alle Vorgänge, die nach Transaktionsbeginn
     * abgebrochen werden. Eine tatsächliche Bezahlung darf im Zusammenhang mit diesem Vorgangstyp
     * nicht erfolgen." — i.e. use it, and only it, to FinishTransaction a TSE transaction that was
     * started for a Vorgang which is then abandoned. Anhang I p. 120 fixes the payload shape: all
     * five Brutto-Steuerumsatz buckets are 0.00 and the `<Zahlungen>` section is EMPTY (a trailing
     * `^`), because no payment may be associated with the aborted Vorgang.
     *
     * Do not hand-roll this string and do not call this directly to abort a transaction — go through
     * `TseTransactionService.abortTransaction`, which owns the full abort semantics. The empty
     * payment section and the exact bucket count are what the TSE-side validator and a
     * Kassennachschau check, and a transaction left open on the WORM device is unrecoverable.
     *
     * **Export obligation:** a Vorgang secured this way still consumes a TSE transaction number and
     * signature counter, and DSFinV-K §2.1 requires every TSE-secured Vorgang to appear as a
     * recognisable Vorgang in the export. Any new caller of this helper must therefore also ensure
     * the aborted Vorgang reaches `transactions.csv` / `transactions_tse.csv` with
     * `BON_TYP = AVBelegabbruch`, otherwise the TSE log shows a signature the export cannot explain.
     */
    fun buildBelegabbruch(): String =
        build(
            processType = ProcessType.KASSENBELEG_V1,
            bonType = BonType.AV_BELEGABBRUCH,
            vatDetail = emptyList(),
            payments = emptyList(),
        )

    /**
     * @param nonTaxableExtra an amount that belongs in the 5th ("nicht steuerbar") brutto slot
     *   without being part of the VAT breakdown — today only a TrinkgeldAN employee tip
     *   (DSFinV-K Anhang I p. 120). It is NOT a [BillVatDetail] row: see
     *   docs/superpowers/plans/2026-09-16-e2e-findings-fixes.md P1.
     */
    fun build(
        processType: ProcessType,
        bonType: BonType? = null,
        vatDetail: List<BillVatDetail>? = null,
        payments: List<Payment>? = null,
        nonTaxableExtra: BigDecimal = BigDecimal.ZERO,
        items: List<OrderItemDto.OrderItemResponseDto>? = null,
        freiText: String? = null,
    ): String {
        val processData =
            when (processType) {
                ProcessType.KASSENBELEG_V1 -> buildBillProcessData(bonType!!, vatDetail!!, payments!!, nonTaxableExtra)
                ProcessType.BESTELLUNG_V1 -> buildOrderProcessData(items!!)
                ProcessType.SONSTIGER_VORGANG -> freiText ?: ""
            }

        // Verify what we just built against the Anhang I grammar FOR THIS processType, before it
        // can reach the TSE. The driver's own validator cannot serve this purpose: it applies the
        // Kassenbeleg grammar to every payload (so it rejects a valid Bestellung-V1), it runs only
        // in TseMode.NORMAL on hardware, and it runs INSIDE FinishTransaction — after
        // transaction_start has burned a transaction number, so a rejection there strands an open
        // transaction on the WORM. Failing here instead means an invalid payload never reaches a
        // device, and the quickSign path — which performs no validation at all — is covered too.
        val verdict = ProcessDataSpecValidator.validate(processType, processData)
        require(verdict.isValid) {
            "refusing to sign malformed ${processType.value} processData: " +
                "${verdict.errors.joinToString("; ")} — payload was '$processData'"
        }
        // Well-formed but internally inconsistent: reported, never fatal. Refusing here would leave
        // the Vorgang UNSIGNED, which is the §146a violation the TSE exists to prevent.
        if (verdict.warnings.isNotEmpty()) {
            logger.warn(
                "processData warning for {}: {} — payload '{}'",
                processType.value,
                verdict.warnings.joinToString("; "),
                processData,
            )
        }
        return processData
    }

    /**
     * The signed amount a payment contributes to a signed receipt. A payment stamped with a
     * [Payment.refundedBillId] is a storno reversal of an original (positive) tender and must
     * be signed negative — the refund direction a Kassennachschau verifies. Every other row
     * passes through unchanged.
     */
    private fun signedAmount(payment: Payment): BigDecimal = if (payment.refundedBillId != null) payment.amount.negate() else payment.amount

    /**
     * Anhang I: „Die Umsätze sind mit exakt zwei Dezimalstellen anzugeben, alle anderen
     * Dezimalstellen werden ggf. abgeschnitten." — TRUNCATE, never round (audit TSER-15).
     * '.' decimal separator, '-' sign, no grouping (toPlainString is locale-independent).
     *
     * Public so [org.example.project.utils.builder.tse.KassenbuchProcessDataBuilder] can still format amounts once the two builders sit in different modules.
     */
    fun fmtAmount(value: BigDecimal): String = value.setScale(2, RoundingMode.DOWN).toPlainString()

    // Anhang I bucket order for the Brutto-Steuerumsätze — each bucket covers the CURRENT
    // AND HISTORICAL statutory rates of its category (audit TSER-16; the ghost 10.5 % of
    // the old whitelist never existed in Germany):
    //   1. Allgemeiner Steuersatz (§12 Abs. 1): 19 %, historical 16 % (07–12/2020)
    //   2. Ermäßigter Steuersatz (§12 Abs. 2): 7 %, historical 5 % (07–12/2020)
    //   3. Durchschnittssatz §24 Abs. 1 Nr. 3: 7.8 % (2025+), hist. 8.4/9.0/9.5/10.7 %
    //   4. Durchschnittssatz §24 Abs. 1 Nr. 1: 5.5 %
    //   5. 0 % (nicht steuerbar / steuerfrei)
    private val BUCKET_GENERAL = setOf("0.19", "0.16")
    private val BUCKET_REDUCED = setOf("0.07", "0.05")
    private val BUCKET_AVG_NR3 = setOf("0.078", "0.084", "0.09", "0.095", "0.107")
    private val BUCKET_AVG_NR1 = setOf("0.055")

    /** Index of the 5th bucket — 0 % / "nicht steuerbar", where a non-taxable tip is signed. */
    private const val NON_TAXABLE_BUCKET = 4

    /** Public so [org.example.project.utils.builder.tse.KassenbuchProcessDataBuilder] can still pick the Anhang I bucket once the two builders sit in different modules. */
    fun bucketIndexFor(rate: BigDecimal): Int {
        val key = rate.stripTrailingZeros().toPlainString()
        return when {
            key in BUCKET_GENERAL -> 0
            key in BUCKET_REDUCED -> 1
            key in BUCKET_AVG_NR3 -> 2
            key in BUCKET_AVG_NR1 -> 3
            rate.compareTo(BigDecimal.ZERO) == 0 -> 4
            // Fail loud: silently misbucketing would SIGN wrong tax data.
            else -> throw IllegalArgumentException(
                "unmapped VAT rate for Kassenbeleg processData: $key",
            )
        }
    }

    private fun buildBillProcessData(
        bonType: BonType,
        vatDetail: List<BillVatDetail>,
        payments: List<Payment>,
        nonTaxableExtra: BigDecimal,
    ): String {
        // --- Brutto-Steuerumsätze: 5 buckets, ACCUMULATED per bucket (several detail rows
        // can share a bucket, e.g. two 0%-item groups). ---
        val buckets = Array(5) { BigDecimal.ZERO }
        vatDetail.forEach { buckets[bucketIndexFor(it.vat.vatRate)] += it.totalAmount }
        // A TrinkgeldAN employee tip is non-taxable and is not turnover, so it is carried OUTSIDE
        // the VAT breakdown and added straight to the 5th slot — where the old 0% BillVatDetail
        // row used to put it, so the signed bytes are unchanged.
        buckets[NON_TAXABLE_BUCKET] += nonTaxableExtra

        // Mehrzweckgutschein-Einlösung: a NEGATIVE 0%-bucket line, never a payment entry —
        // Anhang I example „Gutscheineinlösung": Beleg^60.00_60.00_0.00_0.00_-100.00^20.00:Bar
        // (audit C1; mirrors the CSV model: GV MehrzweckgutscheinEinloesung / Zahlart Keine).
        val voucherTotal = payments.filter { it.paymentType.isVoucher }.sumOf(::signedAmount)
        if (voucherTotal.compareTo(BigDecimal.ZERO) != 0) {
            buckets[NON_TAXABLE_BUCKET] -= voucherTotal
        }
        val tenderPayments = payments.filterNot { it.paymentType.isVoucher }

        val vatString = buckets.joinToString("_") { fmtAmount(it) }

        // --- Zahlungen: Anhang I „Bei mehreren Zahlungsarten sind diese entsprechend zu
        // akkumulieren" and „Zahlungen von 0.00 müssen entfallen" (audit H14). Order:
        // Bar EUR first, Bar foreign alphabetically, then Unbar EUR, Unbar foreign. ---
        val cash = tenderPayments.filter { it.paymentType.isCash }
        val nonCash = tenderPayments.filterNot { it.paymentType.isCash }

        val parts = mutableListOf<String>()

        fun addEntry(
            sum: BigDecimal,
            kind: String,
            currency: String,
        ) {
            if (sum.compareTo(BigDecimal.ZERO) == 0) return
            val suffix = if (currency == "EUR") "" else ":$currency"
            parts.add("${fmtAmount(sum)}:$kind$suffix")
        }
        addEntry(cash.filter { it.currency == "EUR" }.sumOf(::signedAmount), "Bar", "EUR")
        cash
            .filter { it.currency != "EUR" }
            .groupBy { it.currency }
            .toSortedMap()
            .forEach { (currency, list) -> addEntry(list.sumOf(::signedAmount), "Bar", currency) }
        nonCash
            .filter { it.currency == "EUR" }
            .takeIf { it.isNotEmpty() }
            ?.let { addEntry(it.sumOf(::signedAmount), "Unbar", "EUR") }
        nonCash
            .filter { it.currency != "EUR" }
            .groupBy { it.currency }
            .toSortedMap()
            .forEach { (currency, list) -> addEntry(list.sumOf(::signedAmount), "Unbar", currency) }

        val paymentString = parts.joinToString("_")

        return "$bonType^$vatString^$paymentString"
    }

    /**
     * Anhang I „Bestellung" (p. 117): one line per Bestellzeile, Zeilentrenner `\r` (U+000D),
     * Spaltentrenner `;`; `<Preis>` is „der Brutto-Einzelpreis der Bestellzeile" — the CHARGED
     * per-unit gross incl. priced options AND the line discount, rendered with exactly 2 decimals
     * and any further places TRUNCATED (audit TSER-6). This is [chargedUnitPrice] (= the entity
     * finalPrice per single unit), NOT the bare base `unitPrice` (options/discount missing) nor
     * the grouped `finalPrice` (that is the LINE TOTAL, ×quantity — audit C3). fmtAmount truncates
     * (never HALF_UP), matching the AVBestellung CSV rows that carry the same charged price.
     */
    private fun buildOrderProcessData(items: List<OrderItemDto.OrderItemResponseDto>): String =
        items.joinToString("\r") { item ->
            val safeDescription = "\"" + escapeBezeichnung(item.product.productName) + "\""
            "${item.quantity};$safeDescription;${fmtAmount(BigDecimal(item.chargedUnitPrice))}"
        }

    /**
     * m20: the Anhang I p. 116 „Bezeichnung" escape, plus the row separator the spec forgot.
     *
     * The spec mandates exactly one substitution — „Jedes Anführungszeichen in der Beschreibung
     * selbst, ist durch zwei Anführungszeichen zu ersetzen" — and never states that encapsulation
     * protects the „\r" Zeilentrennzeichen the way RFC 4180 does. So a verifier implementing the
     * spec literally (split on `\r`, then on `;`) reads a product name containing CR as an extra
     * Bestellzeile. `ProductDto.validate()` permits such a name, and the payload is SIGNED: unlike
     * the CSV export, it cannot be regenerated once it is in the WORM.
     *
     * CR/LF therefore collapse to a space, identically to `DsfinvkCsvUtils.escapeString` (CONT-7),
     * so the signed Bezeichnung and the exported ARTIKELTEXT for the same product stay
     * byte-comparable in a TAR-vs-DSFinV-K reconciliation.
     */
    private fun escapeBezeichnung(name: String): String =
        name
            .replace("\r\n", " ")
            .replace('\r', ' ')
            .replace('\n', ' ')
            .replace("\"", "\"\"")
}

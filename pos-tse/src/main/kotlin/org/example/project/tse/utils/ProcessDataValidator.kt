package org.example.project.tse.utils

import org.example.project.core.domain.enums.tse.ProcessType
import org.example.project.utils.builder.ProcessDataSpecValidator as SpecValidator
import org.slf4j.LoggerFactory

/**
 * DSFinV-K ProcessData Validator.
 *
 * Validates process data format according to DSFinV-K (Digitale Schnittstelle der
 * Finanzverwaltung für Kassensysteme) specification used in German fiscal systems.
 *
 * Based on use cases from:
 * https://support.gastro-mis.de/support/solutions/articles/36000170993
 *
 * ProcessData Format:
 * ```
 * Type^TaxAmounts^TotalAmount:PaymentMethod
 * ```
 *
 * Examples:
 * - Standard sale: "Beleg^100.00_0.00_0.00_0.00_0.00^100.00:Bar"
 * - Card payment: "Beleg^50.00_0.00_0.00_0.00_0.00^50.00:Unbar"
 * - Multi-use voucher: "Beleg^0.00_0.00_0.00_0.00_100.00^100.00:Bar"
 * - Split tender: "Beleg^100.00_0.00_0.00_0.00_0.00^60.00:Bar_40.00:Unbar"
 * - Foreign currency: "Beleg^100.00_0.00_0.00_0.00_0.00^100.00:Bar:CHF"
 *
 * Components:
 * 1. **Type**: Transaction type (e.g., "Beleg", "Reservierung")
 * 2. **TaxAmounts**: Five tax rates separated by underscores (19%, 7%, special rates)
 * 3. **Payments**: `_`-separated list of `<Betrag>:<Zahlungsart>[:<Währung>]`
 * 4. **Zahlungsart**: ONLY `Bar` or `Unbar` — Anhang I defines no other value here.
 *    Do NOT use the richer DSFinV-K Anhang D vocabulary (ECKarte, Kreditkarte,
 *    ElZahlungsdienstleister, Guthabenkarte, Keine) — that applies to the exported
 *    `datapayment.csv` ZAHLART_TYP, NOT to the signed processData. A card payment
 *    is `:Unbar`. The `:<Währung>` suffix is emitted only for non-EUR.
 *
 * @since 2.0.0
 */
object ProcessDataValidator {

    private val logger = LoggerFactory.getLogger(ProcessDataValidator::class.java)

    /**
     * Validation result with detailed information.
     *
     * @property isValid Whether the process data is valid
     * @property errors List of validation error messages
     * @property warnings List of validation warning messages
     * @property parsedData Parsed components (if valid)
     */
    data class ValidationResult(
        val isValid: Boolean,
        val errors: List<String> = emptyList(),
        val warnings: List<String> = emptyList(),
        val parsedData: ParsedProcessData? = null
    )

    /**
     * Parsed ProcessData components.
     *
     * @property type Transaction type (e.g., "Beleg")
     * @property taxAmounts Array of 5 tax amounts
     * @property totalAmount Total transaction amount
     * @property paymentMethod Payment method (e.g., "Bar", "Karte")
     */
    data class ParsedProcessData(
        val type: String,
        val taxAmounts: List<Double>,
        val totalAmount: Double,
        val paymentMethod: String
    )

    // Task 15 (c): the `ProcessTypes` and `PaymentMethods` vocabularies were REMOVED.
    //
    // They contradicted this file's own KDoc, which states „Zahlungsart: ONLY `Bar` or `Unbar` —
    // Anhang I defines no other value here", while declaring KARTE/EC/KREDIT/RECHNUNG/GUTSCHEIN/
    // ONLINE right beneath it, plus processTypes (Reservierung, Anzahlung, EinzweckGutschein,
    // MehrzweckGutschein, GutscheinEinlösung, Storno) that Anhang I does not define — the closed
    // list is Kassenbeleg-V1 / Bestellung-V1 / SonstigerVorgang, which is `ProcessType`.
    //
    // `build()` and `buildReceipt()` went with them: both used locale-sensitive `"%.2f".format()`,
    // which under a German default locale emits `100,00` and would have produced — and SIGNED —
    // unparseable processData. `ProcessDataBuilder` (pos-server) is the real, spec-correct builder
    // and formats via `BigDecimal.toPlainString()`. A second builder inside the driver that can
    // emit comma decimals is a trap, not a fallback. All six symbols had zero references.

    /**
     * Validate ProcessData format.
     *
     * Checks:
     * 1. Basic format: Type^TaxAmounts^TotalAmount:PaymentMethod
     * 2. Tax amounts: Exactly 5 numeric values separated by underscores
     * 3. Total amount: Valid numeric value
     * 4. Payment method: Non-empty string
     * 5. Mathematical consistency: Sum of tax amounts matches total (with tolerance)
     *
     * @param processData The process data string to validate
     * @param strict If true, enforces strict validation (no warnings)
     * @return ValidationResult with details
     */
    /**
     * Validate ProcessData against the Anhang I grammar for [processTypeValue].
     *
     * Task 15 (a)+(b): this used to apply the **Kassenbeleg-V1 grammar to every payload**, so a
     * valid `Bestellung-V1` (`2;"Eisbecher";3.99`, zero `^`) was rejected outright — and because
     * the check runs inside `FinishTransaction`, i.e. AFTER `transaction_start` has burned a
     * transaction number, the rejection left an open transaction on the WORM that cannot be
     * corrected. It also misparsed multi-tender payloads (`split(":", limit = 2)` treated only the
     * FIRST tender as the total), warning on every legitimate split bill.
     *
     * Both are gone because the grammar now lives in ONE place —
     * [org.example.project.utils.builder.ProcessDataSpecValidator] in `server-common`, which
     * pos-server's `ProcessDataBuilder` also validates against before signing. A second,
     * independently-written classifier is exactly how the round-7 drift happened; there is now
     * nothing to drift.
     *
     * @param processTypeValue the Vorgangstyp this payload is signed under
     *   (`Kassenbeleg-V1`, `Bestellung-V1`, `SonstigerVorgang`) — the grammar is a function of it.
     * @param strict when true, a well-formed but internally inconsistent payload (e.g. Umsätze not
     *   matching Zahlungen) is an error rather than a warning.
     */
    @JvmOverloads
    fun validate(
        processData: String,
        processTypeValue: String = ProcessType.KASSENBELEG_V1.value,
        strict: Boolean = false,
    ): ValidationResult {
        val verdict = SpecValidator.validate(processTypeValue, processData)
        val errors = verdict.errors.toMutableList()
        val warnings = verdict.warnings.toMutableList()

        // Anhang I p. 115 lets Umsätze and Zahlungen differ for foreign currency, so the shared
        // grammar reports a mismatch as a WARNING. `strict` promotes it, preserving this
        // validator's original contract for callers that ask for it.
        if (strict && warnings.isNotEmpty()) {
            errors += warnings
            warnings.clear()
        }

        if (errors.isNotEmpty()) {
            logger.error("Invalid process data format. Errors: ${errors.joinToString("; ")}")
            return ValidationResult(false, errors, warnings)
        }
        return ValidationResult(true, emptyList(), warnings, parseForLogging(processData))
    }

    /**
     * Best-effort decomposition for the debug log only — never a validation authority. Returns null
     * for payloads (Bestellung-V1, SonstigerVorgang) that have no `^` structure to report.
     */
    private fun parseForLogging(processData: String): ParsedProcessData? {
        val parts = processData.split("^")
        if (parts.size != 3) return null
        val taxAmounts = parts[1].split("_").mapNotNull { it.toDoubleOrNull() }
        val tenders = parts[2].split("_").filter { it.isNotEmpty() }
        val total = tenders.sumOf { it.substringBefore(":").toDoubleOrNull() ?: 0.0 }
        return ParsedProcessData(
            type = parts[0].trim(),
            taxAmounts = taxAmounts,
            totalAmount = total,
            // ALL tender methods, not just the first — the old `split(":", limit = 2)` put the
            // remainder of a split payload in here as a garbage "Bar_40.00:Unbar" string.
            paymentMethod = tenders.joinToString(",") { it.substringAfter(":", "") },
        )
    }
}

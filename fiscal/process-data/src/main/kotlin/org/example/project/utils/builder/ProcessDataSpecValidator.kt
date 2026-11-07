/**
 * Anhang I conformance check for TSE processData, applied on our side of the wire.
 *
 * @since 1.0.0
 */
package org.example.project.utils.builder

import org.example.project.core.domain.enums.tse.BonType
import org.example.project.core.domain.enums.tse.ProcessType
import java.math.BigDecimal

/**
 * Validates a processData payload against the Anhang I grammar **for its processType**.
 *
 * Why this exists on the pos-server side rather than only in the driver:
 *
 *  - `pos-tse`'s `ProcessDataValidator` applies the Kassenbeleg-V1 grammar to EVERY payload, so a
 *    `Bestellung-V1` (which has no `^` at all) is rejected. Worse, it runs inside
 *    `FinishTransaction` — after `transaction_start` has already burned a transaction number — so a
 *    rejection strands an open transaction on the WORM, which cannot be corrected. That exact
 *    failure already happened once with negative amounts (round-3 TSER-4).
 *  - The path production actually uses, `quickSign`, performs **no validation at all**.
 *
 * Checking here closes the second gap and makes the first unreachable: an invalid payload is
 * rejected before any TSE call exists to strand. The driver's own validator stays as a last-resort
 * guard; this one is the fiscal boundary.
 *
 * Deliberately a pure function over strings — no device, no mode, no transaction — so every rule is
 * unit-testable, unlike the driver's validator which only executes in `TseMode.NORMAL` on hardware.
 */
object ProcessDataSpecValidator {
    /** Amounts are „stets mit zwei Dezimalstellen" (Anhang I p. 115) — dot decimal, optional sign. */
    private val AMOUNT = Regex("""-?\d+\.\d{2}""")

    /** `<Menge>` is „mit der minimal möglichen Menge von Dezimalstellen" (p. 116) — any plain decimal. */
    private val MENGE = Regex("""-?\d+(\.\d+)?""")

    private val CURRENCY = Regex("""[A-Z]{3}""")

    private val BON_TYPES: Set<String> = BonType.entries.map { it.value }.toSet()

    /** Anhang I defines exactly two Zahlungsarten for the signed payload. */
    private const val BAR = "Bar"
    private const val UNBAR = "Unbar"

    /**
     * [errors] make the payload unusable — it is malformed and must not be signed. [warnings] mean
     * the payload is well-formed but its data is inconsistent; those are reported, never fatal.
     *
     * The split matters operationally: refusing to sign a well-formed-but-unbalanced Beleg would
     * leave the Vorgang UNSIGNED, which is the §146a violation the TSE exists to prevent — strictly
     * worse than signing a recorded document whose sums a Prüfer can question.
     */
    data class Result(
        val isValid: Boolean,
        val errors: List<String> = emptyList(),
        val warnings: List<String> = emptyList(),
    )

    private fun ok() = Result(true)

    private fun fail(vararg errors: String) = Result(false, errors.toList())

    /**
     * @param processType the Vorgangstyp the payload will be signed under — the grammar is a
     *   function of this, which is precisely what the driver's validator ignores.
     */

    /**
     * String overload for callers that only hold the raw processType — notably the pos-tse driver,
     * which reads it off the resolved sign command. An unrecognised value is an ERROR rather than a
     * silent fall-through to the receipt grammar: applying the wrong grammar is how a valid
     * Bestellung-V1 came to be rejected in the first place.
     */
    fun validate(
        processTypeValue: String,
        processData: String,
    ): Result {
        val type =
            ProcessType.entries.find { it.value == processTypeValue }
                ?: return fail(
                    "unknown processType '$processTypeValue' — Anhang I defines only " +
                        ProcessType.entries.joinToString(", ") { it.value },
                )
        return validate(type, processData)
    }

    fun validate(
        processType: ProcessType,
        processData: String,
    ): Result =
        when (processType) {
            ProcessType.KASSENBELEG_V1 -> validateKassenbeleg(processData)
            ProcessType.BESTELLUNG_V1 -> validateBestellung(processData)
            // Anhang I imposes no structure on SonstigerVorgang — it is free text by design. Requiring
            // a shape here would reject legitimate payloads; requiring content stops an empty signature.
            ProcessType.SONSTIGER_VORGANG ->
                if (processData.isBlank()) fail("SonstigerVorgang payload is blank") else ok()
        }

    // ------------------------------------------------------------------ Kassenbeleg-V1

    /** `<BON_TYP>^<5 Brutto-Steuerumsätze>^<Zahlungen>` (Anhang I p. 113-115). */
    private fun validateKassenbeleg(data: String): Result {
        val errors = mutableListOf<String>()

        val parts = data.split("^")
        if (parts.size != 3) {
            return fail(
                "Kassenbeleg-V1 must be <Typ>^<5 Umsätze>^<Zahlungen> — got ${parts.size} " +
                    "'^'-separated parts in '$data'",
            )
        }
        val (bonTyp, umsatzPart, zahlungPart) = parts

        if (bonTyp !in BON_TYPES) {
            errors += "unknown Vorgangstyp '$bonTyp' — expected one of ${BON_TYPES.sorted()}"
        }

        val buckets = umsatzPart.split("_")
        if (buckets.size != 5) {
            errors += "expected 5 Brutto-Steuerumsätze, got ${buckets.size} in '$umsatzPart'"
        }
        buckets.forEach { amount ->
            if (!AMOUNT.matches(amount)) {
                errors += "Umsatz '$amount' must be a two-decimal amount in dot notation " +
                    "(Anhang I p. 115: „stets mit zwei Dezimalstellen“)"
            }
        }

        // An empty payment section is legal — e.g. AVBelegabbruch, which has no revenue and no
        // tender (Anhang B p. 46). Nothing further to check.
        if (zahlungPart.isEmpty()) {
            return if (errors.isEmpty()) ok() else Result(false, errors)
        }

        val tenders = zahlungPart.split("_").map { parseTender(it, errors) }
        if (errors.isNotEmpty()) return Result(false, errors)

        validateTenderOrder(tenders, errors)

        val warnings = mutableListOf<String>()
        validateReconciliation(buckets, tenders, warnings)

        return if (errors.isEmpty()) Result(true, warnings = warnings) else Result(false, errors, warnings)
    }

    private data class Tender(
        val amount: BigDecimal,
        val kind: String,
        val currency: String,
    )

    /** `<Betrag>:<Zahlungsform>[:<Währung>]` — the currency suffix appears only for non-EUR. */
    private fun parseTender(
        raw: String,
        errors: MutableList<String>,
    ): Tender {
        val f = raw.split(":")
        if (f.size !in 2..3) {
            errors += "Zahlung '$raw' must be <Betrag>:<Bar|Unbar>[:<Währung>]"
            return Tender(BigDecimal.ZERO, BAR, "EUR")
        }
        val (amountStr, kind) = f[0] to f[1]
        if (!AMOUNT.matches(amountStr)) {
            errors += "Zahlbetrag '$amountStr' must be a two-decimal amount in dot notation"
        }
        if (kind != BAR && kind != UNBAR) {
            errors += "Zahlungsart '$kind' is not valid — Anhang I defines only 'Bar' and 'Unbar' " +
                "(the richer Anhang D vocabulary belongs to datapayment.csv ZAHLART_TYP)"
        }
        val currency = f.getOrNull(2) ?: "EUR"
        if (f.size == 3 && !CURRENCY.matches(currency)) {
            errors += "Währung '$currency' must be a 3-letter ISO code"
        }
        if (f.size == 3 && currency == "EUR") {
            errors += "the ':<Währung>' suffix is emitted only for non-EUR payments"
        }
        return Tender(
            amount = amountStr.toBigDecimalOrNull() ?: BigDecimal.ZERO,
            kind = kind,
            currency = currency,
        )
    }

    /**
     * Anhang I p. 115 fixes the sequence: Bar EUR, Bar foreign (alphabetical), Unbar EUR, Unbar
     * foreign (alphabetical). The order is part of the signed bytes, so a payload that violates it
     * is not the document the spec describes even though the amounts add up.
     */
    private fun validateTenderOrder(
        tenders: List<Tender>,
        errors: MutableList<String>,
    ) {
        val rank = { t: Tender ->
            when {
                t.kind == BAR && t.currency == "EUR" -> 0
                t.kind == BAR -> 1
                t.currency == "EUR" -> 2
                else -> 3
            }
        }
        val expected = tenders.sortedWith(compareBy({ rank(it) }, { it.currency }))
        if (expected.map { it.kind to it.currency } != tenders.map { it.kind to it.currency }) {
            errors += "Zahlungen are out of the Anhang I p. 115 order (Bar EUR, Bar Fremdwährung " +
                "alphabetisch, Unbar EUR, Unbar Fremdwährung alphabetisch); got " +
                tenders.joinToString(", ") { "${it.kind}:${it.currency}" }
        }
    }

    /**
     * „Grundsätzlich müssen die Summen der Umsätze mit denen der Zahlungen übereinstimmen. Lediglich
     * bei der Verwendung von Fremdwährungen weichen die Summen voneinander ab." (Anhang I p. 115)
     *
     * So the check runs only when every tender is EUR — with a foreign tender the sums legitimately
     * differ and comparing them produces a false alarm on every such Beleg.
     *
     * Reported as a WARNING, never an error: the payload is well-formed, so refusing to sign it
     * would strand the Vorgang unsigned.
     */
    private fun validateReconciliation(
        buckets: List<String>,
        tenders: List<Tender>,
        warnings: MutableList<String>,
    ) {
        if (tenders.any { it.currency != "EUR" }) return
        val revenue = buckets.mapNotNull { it.toBigDecimalOrNull() }.fold(BigDecimal.ZERO, BigDecimal::add)
        val paid = tenders.fold(BigDecimal.ZERO) { acc, t -> acc + t.amount }
        if (revenue.compareTo(paid) != 0) {
            warnings += "Σ Umsätze ($revenue) does not equal Σ Zahlungen ($paid) for a EUR-only " +
                "Beleg (Anhang I p. 115). Reported, not refused: an unsigned Vorgang is the worse " +
                "outcome. Usually means the Beleg carries payments but no BillVatDetail rows."
        }
    }

    // ------------------------------------------------------------------ Bestellung-V1

    /**
     * One line per Bestellzeile, rows separated by `\r`, columns by `;`:
     * `<Menge>;"<Bezeichnung>";<Preis>` (Anhang I p. 116).
     */
    private fun validateBestellung(data: String): Result {
        if (data.isEmpty()) return fail("Bestellung-V1 payload is empty")
        val errors = mutableListOf<String>()

        data.split("\r").forEachIndexed { index, row ->
            val cols = splitOutsideQuotes(row)
            if (cols.size != 3) {
                errors += "Bestellzeile ${index + 1} must be <Menge>;\"<Bezeichnung>\";<Preis> — " +
                    "got ${cols.size} columns in '$row'"
                return@forEachIndexed
            }
            val (menge, bezeichnung, preis) = cols
            if (!MENGE.matches(menge)) {
                errors += "Menge '$menge' in Bestellzeile ${index + 1} is not numeric"
            }
            if (bezeichnung.length < 2 || !bezeichnung.startsWith("\"") || !bezeichnung.endsWith("\"")) {
                errors += "Bezeichnung '$bezeichnung' in Bestellzeile ${index + 1} must be quoted — " +
                    "Anhang I p. 116: „Die Bezeichnung ist immer in Anführungszeichen zu setzen“"
            } else if (bezeichnung.any { it == '\n' }) {
                // A CR cannot appear here (it is the row separator, so it already split the row);
                // an LF would still travel inside the field and forge a line for a reader that
                // breaks on either (m20).
                errors += "Bezeichnung in Bestellzeile ${index + 1} contains a line break, which " +
                    "would forge an extra Bestellzeile"
            }
            if (!AMOUNT.matches(preis)) {
                errors += "Preis '$preis' in Bestellzeile ${index + 1} must carry exactly two " +
                    "decimals (Anhang I p. 116)"
            }
        }
        return if (errors.isEmpty()) ok() else Result(false, errors)
    }

    /**
     * Splits on `;` that sit OUTSIDE the quoted Bezeichnung, so a product name containing a
     * semicolon does not fake an extra column. Doubled `""` is the spec's escape and stays inside.
     */
    private fun splitOutsideQuotes(row: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < row.length) {
            val c = row[i]
            when {
                c == '"' && inQuotes && i + 1 < row.length && row[i + 1] == '"' -> {
                    cur.append("\"\"")
                    i++
                }
                c == '"' -> {
                    inQuotes = !inQuotes
                    cur.append(c)
                }
                c == ';' && !inQuotes -> {
                    out.add(cur.toString())
                    cur.clear()
                }
                else -> cur.append(c)
            }
            i++
        }
        out.add(cur.toString())
        return out
    }
}

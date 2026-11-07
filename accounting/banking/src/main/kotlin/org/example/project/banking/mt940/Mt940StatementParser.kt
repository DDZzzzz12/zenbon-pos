package org.example.project.banking.mt940

import kotlinx.datetime.LocalDate
import org.example.project.banking.StatementMerge
import org.example.project.banking.model.BankStatement
import org.example.project.banking.model.BankStatementFormat
import org.example.project.banking.model.BankTransaction
import org.example.project.banking.model.CreditDebit
import java.math.BigDecimal

/**
 * Assembles [BankStatement]s from MT940 tags.
 *
 * A repeated `:20:` starts a new statement, and several statements in one file are merged the same
 * way CAMT's several `Stmt` elements are — the two formats behave identically so an operator's
 * choice of download does not change what gets stored.
 *
 * `bankTxDomain`/`bankTxFamily`/`bankTxSubFamily` stay null throughout: MT940 has no structured
 * counterpart to CAMT's `BkTxCd`, as `BankTransaction`'s own KDoc records. The DK's GVC is the
 * nearest thing and has no field in the model, so it travels in `rawEntry` rather than being forced
 * into a column that means something else.
 */
object Mt940StatementParser {
    /**
     * `:61:` — `YYMMDD` value date, optional `MMDD` booking date, the `C|D|RC|RD` mark, an optional
     * one-character funds code, the amount, the transaction-type identifier, the customer reference,
     * and optionally `//` plus the bank reference.
     *
     * The identifier is `[NSF]` rather than a hard-coded `N`. `N` dominates German customer
     * statements, but SWIFT also defines `S` (SWIFT transfer) and `F` (first advice); pinning to `N`
     * refused those cleanly but needlessly.
     *
     * Matched against the tag's **first line only** ([Mt940Tag.firstLine]) — see that class for why.
     */
    private val STATEMENT_LINE =
        Regex("""^(\d{6})(\d{4})?(RC|RD|C|D)([A-Z])?([\d,]+)[NSF](.{3})([^/]*)(?://(.*))?$""")

    private val IBAN = Regex("""^[A-Z]{2}\d{2}[A-Z0-9]{10,30}$""")

    fun parse(bytes: ByteArray): BankStatement {
        val tags = Mt940Tokenizer.tokenize(bytes)
        if (tags.isEmpty()) throw Mt940ParseException("no MT940 tags found")

        val statements = splitStatements(tags).map(::buildStatement)
        if (statements.isEmpty()) throw Mt940ParseException("no MT940 statement found")
        return StatementMerge.merge(statements)
    }

    /** A `:20:` tag opens a statement; everything up to the next one belongs to it. */
    private fun splitStatements(tags: List<Mt940Tag>): List<List<Mt940Tag>> {
        val groups = mutableListOf<MutableList<Mt940Tag>>()
        for (tag in tags) {
            // `+=` would resolve to the Iterable overload here and flatten the new group away.
            if (tag.id == "20" || groups.isEmpty()) groups.add(mutableListOf())
            groups.last() += tag
        }
        return groups.filter { group -> group.any { it.id == "61" } || group.any { it.id.startsWith("60") } }
    }

    private fun buildStatement(tags: List<Mt940Tag>): BankStatement {
        val account = tags.firstOrNull { it.id == "25" }?.value?.trim()
        val opening = tags.firstOrNull { it.id == "60F" || it.id == "60M" }?.value?.let(::balance)
        val closing = tags.firstOrNull { it.id == "62F" || it.id == "62M" }?.value?.let(::balance)
        // `:28C:` is the statement/sequence number, but not every bank writes the C — a Luxembourg
        // BIL export uses a bare `:28:`. Matching only `28C` silently drops the statement number on
        // those, and the reconciliation work keys statement identity on it.
        val sequence =
            tags
                .firstOrNull { it.id == "28C" || it.id == "28" }
                ?.value
                ?.trim()
                ?.split("/")

        return BankStatement(
            format = BankStatementFormat.MT940,
            currency = opening?.currency ?: closing?.currency ?: "EUR",
            transactions = entries(tags, opening?.currency ?: closing?.currency ?: "EUR"),
            // ":25: often carries a legacy account number rather than an IBAN" — the model's own
            // KDoc. Deciding by shape rather than by position keeps both banks correct.
            accountIban = account?.takeIf { IBAN.matches(it) },
            accountNumber = account?.takeUnless { IBAN.matches(it) },
            statementNumber = sequence?.getOrNull(0)?.ifBlank { null },
            legalSequenceNumber = sequence?.getOrNull(1)?.ifBlank { null },
            statementDate = closing?.date,
            periodStart = opening?.date,
            periodEnd = closing?.date,
            openingBalance = opening?.amount,
            closingBalance = closing?.amount,
        )
    }

    /**
     * Pairs each `:61:` with the `:86:` that follows it. A `:86:` appearing before any `:61:` — after
     * `:62:`, for instance — is statement-level information and is deliberately ignored: attaching it
     * to an entry would put the bank's footer text on somebody's payment.
     */
    private fun entries(
        tags: List<Mt940Tag>,
        statementCurrency: String,
    ): List<BankTransaction> {
        val result = mutableListOf<BankTransaction>()
        var pending: Mt940Tag? = null

        fun flush(info: Mt940Tag?) {
            pending?.let { result += entry(it, info, statementCurrency) }
            pending = null
        }

        for (tag in tags) {
            when {
                tag.id == "61" -> {
                    flush(null)
                    pending = tag
                }
                tag.id == "86" && pending != null -> flush(tag)
            }
        }
        flush(null)
        return result
    }

    private fun entry(
        line: Mt940Tag,
        info: Mt940Tag?,
        statementCurrency: String,
    ): BankTransaction {
        // The tag's own line first, then the joined value. Both readings are needed and the order
        // decides which wins when both parse:
        //
        // - SWIFT allows an optional supplementary-details subfield on a *second* line
        //   (`/OCMT/EUR4,5//IACC/D3/`). The joined reading swallows it into the bank reference, so
        //   the first line must win when it parses.
        // - Some banks instead hard-wrap the statement line itself mid-value — a real Sparkasse
        //   export breaks after the funds code, leaving `:61:1811261126CR` on its own line. There
        //   the first line cannot parse and the joined value is the only correct reading.
        val match =
            STATEMENT_LINE.matchEntire(line.firstLine.trim())
                ?: STATEMENT_LINE.matchEntire(line.value.trim())
                ?: throw Mt940ParseException("cannot read a :61: statement line from \"${line.value}\"")

        val valueDate = Mt940Values.date(match.groupValues[1])
        val mark = match.groupValues[3]
        val fields = info?.let { Mt86Decoder.decode(it.value) } ?: Mt86Fields()

        return BankTransaction(
            bookingDate = Mt940Values.bookingDate(match.groupValues[2].ifBlank { null }, valueDate),
            valueDate = valueDate,
            amount = Mt940Values.amount(match.groupValues[5]),
            // `:61:` carries no currency of its own — an entry is always in the statement's.
            // Hardcoding EUR made every entry of a non-EUR statement wrong, and the currency
            // invariant then rejected the whole file.
            currency = statementCurrency,
            // RC reverses a credit, RD reverses a debit; the direction is the letter that follows.
            creditDebit = if (mark.endsWith("C")) CreditDebit.CREDIT else CreditDebit.DEBIT,
            accountServicerReference = match.groupValues[8].trim().ifBlank { null },
            endToEndId = fields.endToEndId,
            mandateId = fields.mandateId,
            creditorId = fields.creditorId,
            counterpartyName = fields.counterpartyName,
            counterpartyIban = fields.counterpartyIban,
            counterpartyBic = fields.counterpartyBic,
            remittanceUnstructured = fields.remittance,
            reversalIndicator = mark.startsWith("R"),
            rawEntry = ":61:${line.value}" + (info?.let { "\n:86:${it.value}" } ?: ""),
        )
    }

    /** `:60F:`/`:62F:` — `C|D`, `YYMMDD`, a 3-letter currency, then the amount. */
    private fun balance(raw: String): Balance {
        val text = raw.trim()
        if (text.length < 11) throw Mt940ParseException("cannot read a balance from \"$raw\"")
        val amount = Mt940Values.amount(text.substring(10))
        return Balance(
            amount = if (text.first() == 'D') amount.negate() else amount,
            date = Mt940Values.date(text.substring(1, 7)),
            currency = text.substring(7, 10),
        )
    }

    private data class Balance(
        val amount: BigDecimal,
        val date: LocalDate,
        val currency: String,
    )
}

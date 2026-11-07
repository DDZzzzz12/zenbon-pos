package org.example.project.banking.camt

import kotlinx.datetime.LocalDate
import org.example.project.banking.StatementMerge
import org.example.project.banking.detect.CamtNamespaces
import org.example.project.banking.model.BankStatement
import org.example.project.banking.model.BankStatementFormat
import org.example.project.banking.model.BankTransaction
import org.example.project.banking.model.CreditDebit
import org.example.project.securexml.XPathReader
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.StringWriter
import javax.xml.transform.OutputKeys
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * Maps a camt.053 DOM onto [BankStatement].
 *
 * Pure XML → model: no checks, no I/O, no persistence. [org.example.project.banking.check.BankStatementInvariants]
 * runs afterwards, so a mapping that reads every field correctly but produces impossible arithmetic
 * is caught there rather than half-guarded here.
 *
 * **A `Document` is never shared across threads.** The JDK's DOM is not safe even for concurrent
 * reads — it lazily computes and caches node state without synchronisation, and the `:accounting-einvoice`
 * work measured a thread-safe reader producing silently wrong values in 5 of 10 runs against one
 * shared document. Production is safe because each upload parses its own file; keep it that way.
 */
object Camt053StatementMapper {
    private const val OPENING_BOOKED = "OPBD"
    private const val PREVIOUSLY_CLOSED_BOOKED = "PRCD"
    private const val CLOSING_BOOKED = "CLBD"

    fun map(document: Document): BankStatement {
        val namespace =
            CamtNamespaces.statementNamespaceOrNull(document.documentElement?.namespaceURI)
                ?: throw CamtMappingException(
                    "not a camt.053 statement: root namespace is " +
                        "${document.documentElement?.namespaceURI ?: "absent"}",
                )
        val read = XPathReader(CamtNamespaces.contextFor(namespace))

        val statements = read.elements(document, "/camt:Document/camt:BkToCstmrStmt/camt:Stmt")
        if (statements.isEmpty()) throw CamtMappingException("camt.053 document contains no Stmt element")

        return StatementMerge.merge(statements.map { mapStatement(read, it) })
    }

    /**
     * `internal` so the multi-statement merge can reuse it without re-resolving the namespace.
     */
    internal fun mapStatement(
        read: XPathReader,
        stmt: Element,
    ): BankStatement {
        val currency = read.string(stmt, "camt:Acct/camt:Ccy") ?: "EUR"
        return BankStatement(
            format = BankStatementFormat.CAMT053,
            currency = currency,
            transactions = read.elements(stmt, "camt:Ntry").map { entry(read, it, currency) },
            accountIban = read.string(stmt, "camt:Acct/camt:Id/camt:IBAN"),
            statementNumber = read.string(stmt, "camt:Id"),
            statementDate = date(read, stmt, "camt:CreDtTm"),
            accountBic =
                read.string(stmt, "camt:Acct/camt:Svcr/camt:FinInstnId/camt:BIC")
                    ?: read.string(stmt, "camt:Acct/camt:Svcr/camt:FinInstnId/camt:BICFI"),
            bankName = read.string(stmt, "camt:Acct/camt:Svcr/camt:FinInstnId/camt:Nm"),
            accountNumber = read.string(stmt, "camt:Acct/camt:Id/camt:Othr/camt:Id"),
            legalSequenceNumber = read.string(stmt, "camt:LglSeqNb"),
            periodStart = date(read, stmt, "camt:FrToDt/camt:FrDtTm"),
            periodEnd = date(read, stmt, "camt:FrToDt/camt:ToDtTm"),
            openingBalance = balance(read, stmt, OPENING_BOOKED) ?: balance(read, stmt, PREVIOUSLY_CLOSED_BOOKED),
            closingBalance = balance(read, stmt, CLOSING_BOOKED),
        )
    }

    /**
     * A balance's own `CdtDbtInd` decides its sign. Taking `Amt` at face value silently turns an
     * overdrawn account positive, and every downstream sum inherits the error.
     */
    private fun balance(
        read: XPathReader,
        stmt: Element,
        code: String,
    ): java.math.BigDecimal? {
        val path = "camt:Bal[camt:Tp/camt:CdOrPrtry/camt:Cd='$code']"
        val amount = read.decimal(stmt, "$path/camt:Amt") ?: return null
        return if (read.string(stmt, "$path/camt:CdtDbtInd") == "DBIT") amount.negate() else amount
    }

    private fun entry(
        read: XPathReader,
        ntry: Element,
        statementCurrency: String,
    ): BankTransaction {
        val valueDate =
            date(read, ntry, "camt:ValDt/camt:Dt")
                ?: date(read, ntry, "camt:ValDt/camt:DtTm")
                ?: throw CamtMappingException("Ntry has no ValDt")
        val amount = read.decimal(ntry, "camt:Amt") ?: throw CamtMappingException("Ntry has no Amt")
        val direction =
            when (read.string(ntry, "camt:CdtDbtInd")) {
                "CRDT" -> CreditDebit.CREDIT
                "DBIT" -> CreditDebit.DEBIT
                else -> throw CamtMappingException("Ntry has no usable CdtDbtInd")
            }

        // Exactly one TxDtls carries usable detail. Several means a batch booking: the entry is
        // still one booked amount, but its counterparty is not a single party, and inventing one
        // would be a guess.
        val details = read.elements(ntry, "camt:NtryDtls/camt:TxDtls").singleOrNull()

        return BankTransaction(
            bookingDate =
                date(read, ntry, "camt:BookgDt/camt:Dt")
                    ?: date(read, ntry, "camt:BookgDt/camt:DtTm")
                    ?: valueDate,
            valueDate = valueDate,
            amount = amount,
            currency = read.attribute(ntry, "camt:Amt", "Ccy") ?: statementCurrency,
            creditDebit = direction,
            status = read.string(ntry, "camt:Sts"),
            bankTxDomain = read.string(ntry, "camt:BkTxCd/camt:Domn/camt:Cd"),
            bankTxFamily = read.string(ntry, "camt:BkTxCd/camt:Domn/camt:Fmly/camt:Cd"),
            bankTxSubFamily = read.string(ntry, "camt:BkTxCd/camt:Domn/camt:Fmly/camt:SubFmlyCd"),
            accountServicerReference = read.string(ntry, "camt:AcctSvcrRef"),
            endToEndId = details?.let { read.string(it, "camt:Refs/camt:EndToEndId") },
            mandateId = details?.let { read.string(it, "camt:Refs/camt:MndtId") },
            creditorId = details?.let { firstOf(read, it, creditorIdPaths()) },
            counterpartyName = details?.let { firstOf(read, it, counterpartyNamePaths(direction)) },
            counterpartyIban = details?.let { read.string(it, counterpartyAccountPath(direction)) },
            counterpartyBic = details?.let { firstOf(read, it, counterpartyAgentPaths(direction)) },
            remittanceUnstructured =
                details
                    ?.let { read.elements(it, "camt:RmtInf/camt:Ustrd") }
                    ?.mapNotNull { it.textContent?.trim()?.ifBlank { null } }
                    ?.joinToString(" ")
                    ?.ifBlank { null },
            remittanceStructuredRef = details?.let { read.string(it, "camt:RmtInf/camt:Strd/camt:CdtrRefInf/camt:Ref") },
            reversalIndicator = read.string(ntry, "camt:RvslInd")?.equals("true", ignoreCase = true) ?: false,
            rawEntry = serialise(ntry),
        )
    }

    /** Returns the first of [paths] that yields a value, or null when none does. */
    private fun firstOf(
        read: XPathReader,
        node: Element,
        paths: List<String>,
    ): String? = paths.firstNotNullOfOrNull { read.string(node, it) }

    /**
     * Which party is "the other side" depends on direction: money in came *from* the debtor, money
     * out went *to* the creditor. Reading one side unconditionally produces a statement where half
     * the counterparties are the operator's own account.
     *
     * Two shapes per side, because `camt.053.001.04` wrapped the parties in `Party40Choice`: `.02`
     * writes `Dbtr/Nm`, `.04` and later write `Dbtr/Pty/Nm`. Reading only the `.02` shape leaves
     * every counterparty name null on a modern file — the statement still reconciles, so no
     * arithmetic check catches it, and it is exactly the field reconciliation matches suppliers on.
     */
    private fun counterpartyNamePaths(direction: CreditDebit): List<String> =
        when (direction) {
            CreditDebit.CREDIT -> listOf("camt:RltdPties/camt:Dbtr/camt:Nm", "camt:RltdPties/camt:Dbtr/camt:Pty/camt:Nm")
            CreditDebit.DEBIT -> listOf("camt:RltdPties/camt:Cdtr/camt:Nm", "camt:RltdPties/camt:Cdtr/camt:Pty/camt:Nm")
        }

    /** `CashAccount` did not change shape across these versions, so one path serves both. */
    private fun counterpartyAccountPath(direction: CreditDebit): String =
        when (direction) {
            CreditDebit.CREDIT -> "camt:RltdPties/camt:DbtrAcct/camt:Id/camt:IBAN"
            CreditDebit.DEBIT -> "camt:RltdPties/camt:CdtrAcct/camt:Id/camt:IBAN"
        }

    /** `BIC` was renamed `BICFI` in the later financial-institution identification. */
    private fun counterpartyAgentPaths(direction: CreditDebit): List<String> {
        val agent = if (direction == CreditDebit.CREDIT) "camt:DbtrAgt" else "camt:CdtrAgt"
        return listOf(
            "camt:RltdAgts/$agent/camt:FinInstnId/camt:BIC",
            "camt:RltdAgts/$agent/camt:FinInstnId/camt:BICFI",
        )
    }

    /**
     * The SEPA creditor identifier. Four shapes: `Party40Choice` adds the `Pty` level, and a sender
     * may file the identifier under either the private or the organisation identification.
     */
    private fun creditorIdPaths(): List<String> =
        listOf("camt:Cdtr", "camt:Cdtr/camt:Pty").flatMap { base ->
            listOf(
                "camt:RltdPties/$base/camt:Id/camt:PrvtId/camt:Othr/camt:Id",
                "camt:RltdPties/$base/camt:Id/camt:OrgId/camt:Othr/camt:Id",
            )
        }

    /**
     * ISO 20022 dates are `YYYY-MM-DD` or a full `YYYY-MM-DDThh:mm:ss`. Only the date part reaches
     * the model — a booking has a day, not a moment, and the time a bank stamps on it is its own
     * processing detail.
     */
    private fun date(
        read: XPathReader,
        node: Element,
        expression: String,
    ): LocalDate? {
        val raw = read.string(node, expression) ?: return null
        val datePart = raw.substringBefore('T')
        return runCatching { LocalDate.parse(datePart) }
            .getOrElse { throw CamtMappingException("cannot read a date from \"$raw\" at $expression", it) }
    }

    /**
     * Serialises the element back to text for `rawEntry` — the additive escape hatch for fields this
     * model does not carry. No business logic reads it; it exists so nothing the bank sent is lost
     * on the way in.
     *
     * Uses `TransformerFactory`, which is not one of the parser factories the module guard forbids:
     * it writes a DOM out rather than reading untrusted bytes in, so it is not an XXE surface. It is
     * still configured to disable external access, because a `Transformer` obtained from a
     * misconfigured factory could otherwise resolve a stylesheet reference.
     */
    private fun serialise(element: Element): String? =
        runCatching {
            val factory =
                javax.xml.transform.TransformerFactory
                    .newInstance()
            factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "")
            factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "")
            val writer = StringWriter()
            factory
                .newTransformer()
                .apply {
                    setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
                    setOutputProperty(OutputKeys.INDENT, "no")
                }.transform(DOMSource(element), StreamResult(writer))
            writer.toString()
        }.getOrNull()
}

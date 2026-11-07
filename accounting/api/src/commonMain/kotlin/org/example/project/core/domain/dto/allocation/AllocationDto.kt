package org.example.project.core.domain.dto.allocation

import kotlinx.serialization.Serializable
import org.example.project.core.domain.serialization.MoneyStringSerializer

/**
 * The Zuordnung wire contract: the queue of bank transactions still carrying a residual, the
 * candidates of each target kind they can be settled against, and the writes that settle them.
 *
 * **Every amount is a decimal string.** Money never crosses the wire as a JSON number.
 *
 * The ids on [CreateRequestDto] are one per kind rather than one polymorphic id, matching
 * `payment_allocation` itself: only the id belonging to the declared kind is ever set.
 */
object AllocationDto {
    /**
     * Which rows the queue returns.
     *
     * The spec's third chip, *Vorschlag vorhanden*, needs the matcher from slice 5 and is absent
     * rather than stubbed: a chip that always yields nothing reads as "no suggestions exist",
     * which is a different claim from "suggestions are not built yet".
     */
    @Serializable
    enum class QueueFilter { NICHT_ZUGEORDNET, ALLE }

    /** One assignment already made against a transaction. */
    @Serializable
    data class AllocationRowDto(
        val id: Long,
        val sourceKind: String,
        val targetKind: String,
        val amount: String,
        val matchedBy: String,
        val invoiceId: Long? = null,
        val invoiceNumber: String? = null,
        /** The AR mirror of [invoiceId] — set for a `SALES_INVOICE` allocation. */
        val salesInvoiceId: Long? = null,
        val salesInvoiceNumber: String? = null,
        /**
         * Which contract instalment this settled, named and dated. Without them a
         * `CONTRACT_INSTALMENT` allocation renders as its bare kind, saying nothing about *which*
         * expected payment it covered.
         */
        val contractInstalmentId: Long? = null,
        val contractName: String? = null,
        val instalmentDueDate: String? = null,
        val accountNo: String? = null,
        val bookingText: String? = null,
        val note: String? = null,
    )

    /**
     * One queue row.
     *
     * [openAmount] is the **effective** residual: the stored `open_amount` when it has one, else the
     * full [amount]. A NULL column means nobody has computed a residual yet, not that the entry is
     * assigned.
     */
    @Serializable
    data class QueueRowDto(
        val transactionId: Long,
        val bookingDate: String,
        val valueDate: String,
        val amount: String,
        val openAmount: String,
        val currency: String = "EUR",
        /** `CREDIT` | `DEBIT` — [amount] is always positive, direction lives here. */
        val creditDebit: String,
        val counterpartyName: String? = null,
        val counterpartyIban: String? = null,
        val remittance: String? = null,
        val allocations: List<AllocationRowDto> = emptyList(),
        /**
         * The bank flagged this entry as a reversal — typically a returned direct debit
         * (Rücklastschrift). The nightly *Stornolauf* voids the allocations of the debit it
         * reverses, which puts that debit back into this queue; the flag is what lets the operator
         * see why it reappeared.
         */
        val reversalIndicator: Boolean = false,
    )

    @Serializable
    data class QueueResponseDto(
        val items: List<QueueRowDto>,
        val total: Long,
    )

    /** A candidate target: an invoice that still owes something. */
    @Serializable
    data class OpenInvoiceDto(
        val invoiceId: Long,
        val invoiceNumber: String? = null,
        val supplierId: Long? = null,
        val supplierName: String? = null,
        val issueDate: String? = null,
        val dueDate: String? = null,
        val grossTotal: String? = null,
        val openAmount: String,
        val currency: String = "EUR",
    )

    @Serializable
    data class OpenInvoiceListDto(
        val items: List<OpenInvoiceDto>,
        val total: Long,
    )

    /** A candidate target: a case-C (STANDALONE) invoice that still expects money — the AR mirror of [OpenInvoiceDto]. */
    @Serializable
    data class OpenSalesInvoiceDto(
        val salesInvoiceId: Long,
        val invoiceNumber: String? = null,
        val debtorId: Long? = null,
        /** `sales_invoice.buyer_name` — the snapshot, not the master record. */
        val debtorName: String,
        val issueDate: String,
        val dueDate: String? = null,
        val grossTotal: String? = null,
        val openAmount: String,
        val currency: String = "EUR",
    )

    @Serializable
    data class OpenSalesInvoiceListDto(
        val items: List<OpenSalesInvoiceDto>,
        val total: Long,
    )

    /**
     * A contract instalment offered as an allocation target.
     *
     * [openAmount] is the effective residual — `open_amount`, else `expected_amount` — as a decimal
     * string, never a number. [contractName] carries the contract so the operator picks by what the
     * payment is *for*, not by an id.
     */
    @Serializable
    data class OpenInstalmentDto(
        val instalmentId: Long,
        val contractId: Long,
        val contractName: String? = null,
        val supplierName: String? = null,
        val dueDate: String,
        val expectedAmount: String? = null,
        val openAmount: String,
        val currency: String = "EUR",
        val status: String,
    )

    @Serializable
    data class OpenInstalmentListDto(
        val items: List<OpenInstalmentDto>,
        val total: Long,
    )

    @Serializable
    data class CreateRequestDto(
        val sourceKind: String,
        val targetKind: String,
        // Money crosses the wire as a JSON string, never a number. The request Json is isLenient,
        // which would otherwise coerce a bare 25.00 into "25.00" silently (D7); this serializer
        // inspects the JsonDecoder and rejects a numeric value.
        @Serializable(with = MoneyStringSerializer::class)
        val amount: String,
        val bankTransactionId: Long? = null,
        val kassenbuchHeaderId: Long? = null,
        /** Required when `sourceKind` is `TILL_RECEIPT`: the clearing Bill the money arrived on. */
        val billId: Long? = null,
        val invoiceId: Long? = null,
        /** One per target kind, like [invoiceId] — set for a `SALES_INVOICE` target. */
        val salesInvoiceId: Long? = null,
        val contractInstalmentId: Long? = null,
        val counterKassenbuchHeaderId: Long? = null,
        val counterBankAccountId: Long? = null,
        val accountNo: String? = null,
        val bookingText: String? = null,
        val note: String? = null,
        /**
         * How this allocation came about: `MANUAL` or `SUGGESTION_ACCEPTED`, nothing else.
         *
         * `AUTO_RULE` is refused with a 400 rather than accepted or quietly downgraded — nothing
         * applies an allocation automatically, and a client claiming otherwise would corrupt the one
         * field the reporting reads to learn which rules actually help.
         */
        val matchedBy: String = "MANUAL",
        /**
         * Which signal of the suggestion ladder produced this, by its `MatchSignal` name — null for a
         * manual assignment.
         *
         * A `MANUAL` allocation naming a signal is refused with a 400 rather than stored: it would be
         * a human's own reading counted as a rule's success, corrupting exactly the numbers the
         * column exists to produce.
         */
        val matchedSignal: String? = null,
    )

    /**
     * The written allocation plus both residuals it changed, so the editor can render its running
     * *Rest offen* without a second round trip.
     *
     * Either residual is null when that side has none to keep: a cash-book source, or a terminal
     * target such as a fee.
     */
    @Serializable
    data class CreateResponseDto(
        val allocation: AllocationRowDto,
        val sourceOpenAmount: String? = null,
        val targetOpenAmount: String? = null,
    )
}

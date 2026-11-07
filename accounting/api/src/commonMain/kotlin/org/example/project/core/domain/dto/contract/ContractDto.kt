package org.example.project.core.domain.dto.contract

import kotlinx.serialization.Serializable

/**
 * Wire contract for recurring obligations — rent, Strom, Telefon, a leasing agreement — and the
 * instalments they fall due in.
 *
 * **Every money field is a decimal `String`**, never a number: the server writes it with
 * `toExactDecimalString()` so a `DECIMAL(19,4)` residual keeps its scale, and parses an inbound one
 * with `toBigDecimalOrNull()`. `dueDay` and `noticePeriodDays` are counts, not money.
 *
 * Dates cross as ISO `yyyy-MM-dd` strings.
 *
 * There is no delete: a contract deactivates via [UpdateRequestDto.isActive], because an instalment
 * that has already been allocated against is history.
 */
object ContractDto {
    @Serializable
    data class ResponseDto(
        val id: Long,
        val name: String,
        val description: String? = null,
        val supplierId: Long? = null,
        val supplierName: String? = null,
        /** Decimal string. Money never crosses the wire as a number. */
        val expectedAmount: String? = null,
        val tolerancePct: String? = null,
        val toleranceAbs: String? = null,
        val interval: String,
        val dueDay: Int,
        val dueWeekday: Int? = null,
        val startDate: String,
        val endDate: String? = null,
        val noticePeriodDays: Int? = null,
        val expectsInvoice: Boolean = false,
        /**
         * SEPA mandate reference quoted on every direct debit collected under this agreement.
         *
         * On the contract rather than the supplier because a mandate is granted per agreement — two
         * contracts with the same supplier carry different mandates. Null until recorded; a blank is
         * stored as null.
         */
        val sepaMandateId: String? = null,
        val defaultAccountNo: String? = null,
        val defaultBuCode: String? = null,
        val defaultCostCenter: String? = null,
        val documentId: Long? = null,
        val isActive: Boolean = true,
        /**
         * The shop's configured currency (ISO 4217).
         *
         * A contract is our own record rather than a counterparty's document, so it has no currency
         * of its own the way an invoice does — this is the shop's, resolved server-side. It rides on
         * the DTO so the panel never falls back to a hardcoded "EUR".
         */
        val currency: String = "EUR",
        /**
         * Earliest instalment not yet settled — neither `PAID` nor `WAIVED`. Null when nothing is
         * outstanding. **Derived**, never stored, for the same reason [InstalmentDto.status] is.
         */
        val nextDueDate: String? = null,
        /** Latest instalment that derives as `PAID`. Null when none is. Derived, never stored. */
        val lastPaymentDate: String? = null,
        /**
         * How many things this contract is currently complaining about — see [ContractAlertDto].
         *
         * It rides on the list response so the table can show a per-row count without a request per
         * row. Zero on a deactivated contract: an operator who switched it off is not asking to be
         * nagged about it, and the alerts endpoint leaves those out for the same reason.
         */
        val alertCount: Int = 0,
    )

    @Serializable
    data class ListResponseDto(
        val items: List<ResponseDto>,
        val total: Long,
    )

    @Serializable
    data class CreateRequestDto(
        val name: String,
        val interval: String,
        val dueDay: Int,
        val dueWeekday: Int? = null,
        val startDate: String,
        val description: String? = null,
        val supplierId: Long? = null,
        val expectedAmount: String? = null,
        val tolerancePct: String? = null,
        val toleranceAbs: String? = null,
        val endDate: String? = null,
        val noticePeriodDays: Int? = null,
        val expectsInvoice: Boolean = false,
        /** See [ResponseDto.sepaMandateId]. A blank is stored as null. */
        val sepaMandateId: String? = null,
        val defaultAccountNo: String? = null,
        val defaultBuCode: String? = null,
        val defaultCostCenter: String? = null,
        val documentId: Long? = null,
    ) {
        /** Returns field names that failed validation; empty means valid. */
        fun validate(): List<String> = validateContractFields(name, dueDay, interval, dueWeekday)
    }

    /** A full replace of the editable fields; deactivation rides on [isActive]. */
    @Serializable
    data class UpdateRequestDto(
        val name: String,
        val interval: String,
        val dueDay: Int,
        val dueWeekday: Int? = null,
        val startDate: String,
        val description: String? = null,
        val supplierId: Long? = null,
        val expectedAmount: String? = null,
        val tolerancePct: String? = null,
        val toleranceAbs: String? = null,
        val endDate: String? = null,
        val noticePeriodDays: Int? = null,
        val expectsInvoice: Boolean = false,
        /** See [ResponseDto.sepaMandateId]. A blank is stored as null. */
        val sepaMandateId: String? = null,
        val defaultAccountNo: String? = null,
        val defaultBuCode: String? = null,
        val defaultCostCenter: String? = null,
        val documentId: Long? = null,
        val isActive: Boolean = true,
    ) {
        fun validate(): List<String> = validateContractFields(name, dueDay, interval, dueWeekday)
    }

    /**
     * A `dueDay` outside 1..31 is rejected here as well as by `InstalmentSchedule`, which `require`s
     * it, as is a `dueWeekday` outside 1..7 for a WEEKLY contract — an `IllegalArgumentException`
     * surfacing from the 02:00 job is a much worse way to learn about a bad contract than a 400 at
     * the moment it is created.
     *
     * `dueWeekday` is required for WEEKLY and forbidden for everything else. Forbidden rather than
     * ignored: a stored weekday on a monthly contract would read, correctly, as a setting that does
     * something, and it does not.
     */
    private fun validateContractFields(
        name: String,
        dueDay: Int,
        interval: String,
        dueWeekday: Int?,
    ): List<String> =
        buildList {
            if (name.isBlank()) add("name")
            if (dueDay !in 1..31) add("dueDay")
            // Normalised the same way ContractServiceImpl.parseInterval reads it — a bare `==`
            // against "WEEKLY" would let "weekly" or " WEEKLY " slip past this guard and still be
            // accepted downstream as WEEKLY, reintroducing the null-dueWeekday contract this
            // validator exists to reject. Keep this in agreement with parseInterval; do not simplify.
            if (interval.trim().uppercase() == "WEEKLY") {
                if (dueWeekday !in 1..7) add("dueWeekday")
            } else if (dueWeekday != null) {
                add("dueWeekday")
            }
        }

    /** One expected payment. [status] is **derived**, never the stored column — see the service. */
    @Serializable
    data class InstalmentDto(
        val id: Long,
        val contractId: Long,
        val dueDate: String,
        val expectedAmount: String? = null,
        val openAmount: String? = null,
        val status: String,
        val invoiceId: Long? = null,
        val invoiceNumber: String? = null,
        /** The shop's configured currency — see [ResponseDto.currency]. */
        val currency: String = "EUR",
    )

    @Serializable
    data class InstalmentListDto(
        val items: List<InstalmentDto>,
        val total: Long,
    )
}

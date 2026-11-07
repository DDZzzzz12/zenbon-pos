package org.example.project.core.domain.dto.debtor

import kotlinx.serialization.Serializable
import org.example.project.core.domain.enums.salesinvoice.EInvoiceFormatPreference
import org.example.project.core.utils.EmailFormat

/**
 * Wire contract for debtor master data — the AR mirror of
 * [org.example.project.core.domain.dto.supplier.SupplierDto].
 *
 * Carries no money. A case-A invoice is paid before it is written, so a debtor has no open total
 * until case B or case C exists; a field that would always read `"0.00"` is worse than an absent
 * one. `paymentTermsDays` is a count of days, not an amount.
 */
object DebtorDto {
    @Serializable
    data class ResponseDto(
        val id: Long,
        val name: String,
        val legalName: String? = null,
        val vatId: String? = null,
        val taxNumber: String? = null,
        val street: String? = null,
        val zip: String? = null,
        val city: String? = null,
        val country: String? = null,
        val email: String? = null,
        val phone: String? = null,
        /**
         * The DATEV Personenkonto. Null until the Steuerberater assigns one, and never generated:
         * inventing 10001 for a customer they numbered 10247 costs a quarter's remapping.
         */
        val debtorNo: String? = null,
        val paymentTermsDays: Int? = null,
        /**
         * Default Erlöskonto for STANDALONE invoices, pre-filling `sales_invoice_booking.revenue_account_no`.
         * Null until the shop's account plan or the Steuerberater assigns one — nothing here invents accounts.
         */
        val defaultRevenueAccountNo: String? = null,
        /** Default BU-Schlüssel for STANDALONE invoices, pre-filling `sales_invoice_booking.bu_code`. */
        val defaultBuCode: String? = null,
        val isActive: Boolean = true,
        val notes: String? = null,
        /**
         * How many outgoing invoices reference this debtor, cancelled ones included. Zero means the
         * row may be deleted outright; anything else means deactivate — those invoices are kept ten
         * years under §147 AO and must keep pointing at the party they were issued to.
         */
        val salesInvoiceCount: Long = 0,
        /** How this debtor prefers to receive their invoices. Default is plain PDF. Slice 4e. */
        val einvoiceFormat: EInvoiceFormatPreference = EInvoiceFormatPreference.NONE,
    )

    @Serializable
    data class ListResponseDto(
        val items: List<ResponseDto>,
        val total: Long,
    )

    @Serializable
    data class CreateRequestDto(
        val name: String,
        val legalName: String? = null,
        val vatId: String? = null,
        val taxNumber: String? = null,
        val street: String? = null,
        val zip: String? = null,
        val city: String? = null,
        val country: String? = null,
        val email: String? = null,
        val phone: String? = null,
        val debtorNo: String? = null,
        val paymentTermsDays: Int? = null,
        val defaultRevenueAccountNo: String? = null,
        val defaultBuCode: String? = null,
        val notes: String? = null,
        val einvoiceFormat: EInvoiceFormatPreference = EInvoiceFormatPreference.NONE,
    ) {
        /** Field names that failed validation; empty means valid. */
        fun validate(): List<String> = debtorFieldErrors(name, email)
    }

    /** A full replace of the editable fields, not a patch — an omitted optional becomes null. */
    @Serializable
    data class UpdateRequestDto(
        val name: String,
        val legalName: String? = null,
        val vatId: String? = null,
        val taxNumber: String? = null,
        val street: String? = null,
        val zip: String? = null,
        val city: String? = null,
        val country: String? = null,
        val email: String? = null,
        val phone: String? = null,
        val debtorNo: String? = null,
        val paymentTermsDays: Int? = null,
        val defaultRevenueAccountNo: String? = null,
        val defaultBuCode: String? = null,
        val notes: String? = null,
        val isActive: Boolean = true,
        val einvoiceFormat: EInvoiceFormatPreference = EInvoiceFormatPreference.NONE,
    ) {
        fun validate(): List<String> = debtorFieldErrors(name, email)
    }

    /**
     * The rules both requests share: a name is required, and an e-mail, when given, must look like one
     * ([EmailFormat], the same check the adminApp form runs on Save).
     */
    private fun debtorFieldErrors(
        name: String,
        email: String?,
    ): List<String> =
        buildList {
            if (name.isBlank()) add(FIELD_NAME)
            if (!EmailFormat.isAcceptable(email)) add(FIELD_EMAIL)
        }

    /** Field name reported for a blank name, in `validate()` and in the 400's field errors. */
    const val FIELD_NAME = "name"

    /** Field name reported for a malformed e-mail, in `validate()` and in the 400's field errors. */
    const val FIELD_EMAIL = "email"
}

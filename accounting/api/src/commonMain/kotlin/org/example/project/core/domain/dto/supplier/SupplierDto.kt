package org.example.project.core.domain.dto.supplier

import kotlinx.serialization.Serializable
import org.example.project.core.domain.serialization.MoneyStringSerializer
import org.example.project.core.utils.EmailFormat

/**
 * Wire contract for creditor master data.
 *
 * `paymentTermsDays` is a count, not money. The two computed aggregates on [ResponseDto]
 * ([ResponseDto.openTotal], [ResponseDto.ytdSpend]) are money, and follow the repo-wide rule: a
 * decimal string, never a JSON number — guarded by [MoneyStringSerializer].
 */
object SupplierDto {
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
        val creditorNo: String? = null,
        /**
         * SEPA Creditor Identifier — the party collecting a direct debit. Not [creditorNo], which is
         * the DATEV Kreditorenkonto and an unrelated number.
         */
        val sepaCreditorId: String? = null,
        val defaultAccountNo: String? = null,
        val defaultBuCode: String? = null,
        val defaultCostCenter: String? = null,
        val paymentTermsDays: Int? = null,
        val isProvisional: Boolean = false,
        val isActive: Boolean = true,
        val notes: String? = null,
        val knownIbans: List<String> = emptyList(),
        /**
         * Sum of the residual (`invoice.open_amount > 0`) across this supplier's invoices — what we
         * still owe them. A decimal string ("0.00" when nothing is open); null only on an older
         * server that never computed it.
         */
        @Serializable(with = MoneyStringSerializer::class)
        val openTotal: String? = null,
        /**
         * Sum of what has been allocated to this supplier's invoices in the current calendar year —
         * spend-to-date. A decimal string ("0.00" when nothing paid this year).
         */
        @Serializable(with = MoneyStringSerializer::class)
        val ytdSpend: String? = null,
        /**
         * The shop's default currency (ISO 4217) — [openTotal] and [ytdSpend] are shop-level sums, so
         * they carry no per-invoice currency; the client formats them with this. Defaults to EUR.
         */
        val currency: String = "EUR",
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
        val creditorNo: String? = null,
        /** See [ResponseDto.sepaCreditorId]. A blank is stored as null. */
        val sepaCreditorId: String? = null,
        val defaultAccountNo: String? = null,
        val defaultBuCode: String? = null,
        val defaultCostCenter: String? = null,
        val paymentTermsDays: Int? = null,
        val notes: String? = null,
    ) {
        /** Returns field names that failed validation; empty means valid. */
        fun validate(): List<String> = supplierFieldErrors(name, email)
    }

    /**
     * A full replace of the editable master-data fields. Confirming a provisional supplier is a
     * side effect of a successful update: an operator who has checked the details has confirmed it.
     */
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
        val creditorNo: String? = null,
        /** See [ResponseDto.sepaCreditorId]. A blank is stored as null. */
        val sepaCreditorId: String? = null,
        val defaultAccountNo: String? = null,
        val defaultBuCode: String? = null,
        val defaultCostCenter: String? = null,
        val paymentTermsDays: Int? = null,
        val notes: String? = null,
        val isActive: Boolean = true,
    ) {
        fun validate(): List<String> = supplierFieldErrors(name, email)
    }

    /**
     * Shared by both requests: a name is required, and an e-mail, when given, must look like one. The
     * supplier's e-mail is the same `business_partner.email` column a debtor's is, so it gets the same
     * [EmailFormat] check.
     */
    private fun supplierFieldErrors(
        name: String,
        email: String?,
    ): List<String> =
        buildList {
            if (name.isBlank()) add(FIELD_NAME)
            if (!EmailFormat.isAcceptable(email)) add(FIELD_EMAIL)
        }

    /** Field name reported for a blank name. */
    const val FIELD_NAME = "name"

    /** Field name reported for a malformed e-mail. */
    const val FIELD_EMAIL = "email"
}

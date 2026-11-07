package org.example.project.admin.features.debtors.presentation

import org.example.project.core.domain.dto.debtor.DebtorDto
import org.example.project.core.domain.enums.salesinvoice.EInvoiceFormatPreference

/**
 * The editable projection of a debtor. Every field is a `String` because that is what a text box
 * holds; conversion happens once, in [toCreateRequest] / [toUpdateRequest].
 */
data class DebtorForm(
    val name: String = "",
    val legalName: String = "",
    val vatId: String = "",
    val taxNumber: String = "",
    val street: String = "",
    val zip: String = "",
    val city: String = "",
    val country: String = "",
    val email: String = "",
    val phone: String = "",
    val debtorNo: String = "",
    val paymentTermsDays: String = "",
    val revenueAccountNo: String = "",
    val buCode: String = "",
    val notes: String = "",
    val isActive: Boolean = true,
    val einvoiceFormat: EInvoiceFormatPreference = EInvoiceFormatPreference.NONE,
) {
    companion object {
        fun from(dto: DebtorDto.ResponseDto) =
            DebtorForm(
                name = dto.name,
                legalName = dto.legalName.orEmpty(),
                vatId = dto.vatId.orEmpty(),
                taxNumber = dto.taxNumber.orEmpty(),
                street = dto.street.orEmpty(),
                zip = dto.zip.orEmpty(),
                city = dto.city.orEmpty(),
                country = dto.country.orEmpty(),
                email = dto.email.orEmpty(),
                phone = dto.phone.orEmpty(),
                debtorNo = dto.debtorNo.orEmpty(),
                paymentTermsDays = dto.paymentTermsDays?.toString().orEmpty(),
                revenueAccountNo = dto.defaultRevenueAccountNo.orEmpty(),
                buCode = dto.defaultBuCode.orEmpty(),
                notes = dto.notes.orEmpty(),
                isActive = dto.isActive,
                einvoiceFormat = dto.einvoiceFormat,
            )
    }

    /**
     * A blank optional field becomes `null`, never an empty string or a zero: the server does a full
     * replace, so `""` would store an empty value where the operator meant "not set" — and an empty
     * debtor number stored as `""` would collide with the next debtor left equally blank.
     */
    fun toCreateRequest() =
        DebtorDto.CreateRequestDto(
            name = name.trim(),
            legalName = legalName.blankToNull(),
            vatId = vatId.blankToNull(),
            taxNumber = taxNumber.blankToNull(),
            street = street.blankToNull(),
            zip = zip.blankToNull(),
            city = city.blankToNull(),
            country = country.blankToNull(),
            email = email.blankToNull(),
            phone = phone.blankToNull(),
            debtorNo = debtorNo.blankToNull(),
            paymentTermsDays = paymentTermsDays.trim().toIntOrNull(),
            defaultRevenueAccountNo = revenueAccountNo.blankToNull(),
            defaultBuCode = buCode.blankToNull(),
            notes = notes.blankToNull(),
            einvoiceFormat = einvoiceFormat,
        )

    fun toUpdateRequest() =
        DebtorDto.UpdateRequestDto(
            name = name.trim(),
            legalName = legalName.blankToNull(),
            vatId = vatId.blankToNull(),
            taxNumber = taxNumber.blankToNull(),
            street = street.blankToNull(),
            zip = zip.blankToNull(),
            city = city.blankToNull(),
            country = country.blankToNull(),
            email = email.blankToNull(),
            phone = phone.blankToNull(),
            debtorNo = debtorNo.blankToNull(),
            paymentTermsDays = paymentTermsDays.trim().toIntOrNull(),
            defaultRevenueAccountNo = revenueAccountNo.blankToNull(),
            defaultBuCode = buCode.blankToNull(),
            notes = notes.blankToNull(),
            isActive = isActive,
            einvoiceFormat = einvoiceFormat,
        )
}

private fun String.blankToNull(): String? = trim().takeIf { it.isNotEmpty() }

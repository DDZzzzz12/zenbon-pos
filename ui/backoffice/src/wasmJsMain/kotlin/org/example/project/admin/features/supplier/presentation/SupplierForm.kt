package org.example.project.admin.features.supplier.presentation

import org.example.project.core.domain.dto.supplier.SupplierDto

/**
 * The editable projection of a supplier. Every field is a `String` because that is what a text box
 * holds; conversion happens once, at [toUpdateRequest].
 */
data class SupplierForm(
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
    val creditorNo: String = "",
    val defaultAccountNo: String = "",
    val defaultBuCode: String = "",
    val defaultCostCenter: String = "",
    val paymentTermsDays: String = "",
    val notes: String = "",
    val isActive: Boolean = true,
) {
    companion object {
        fun from(dto: SupplierDto.ResponseDto) =
            SupplierForm(
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
                creditorNo = dto.creditorNo.orEmpty(),
                defaultAccountNo = dto.defaultAccountNo.orEmpty(),
                defaultBuCode = dto.defaultBuCode.orEmpty(),
                defaultCostCenter = dto.defaultCostCenter.orEmpty(),
                paymentTermsDays = dto.paymentTermsDays?.toString().orEmpty(),
                notes = dto.notes.orEmpty(),
                isActive = dto.isActive,
            )
    }

    /**
     * A blank optional field becomes `null`, never an empty string or a zero: the server does a full
     * replace, so "" would store an empty value where the operator meant "not set", and `0` payment
     * terms would make every invoice from this supplier overdue the day it arrives.
     */

    /**
     * The create counterpart of [toUpdateRequest].
     *
     * Same blank-to-null discipline: the server stores a blank as a value, so an untouched box would
     * otherwise become an empty string that reads as "stated, and empty" rather than "not stated".
     * `isActive` has no place here — a creditor is created active, and deactivating one is an edit.
     */
    fun toCreateRequest() =
        SupplierDto.CreateRequestDto(
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
            creditorNo = creditorNo.blankToNull(),
            defaultAccountNo = defaultAccountNo.blankToNull(),
            defaultBuCode = defaultBuCode.blankToNull(),
            defaultCostCenter = defaultCostCenter.blankToNull(),
            paymentTermsDays = paymentTermsDays.trim().toIntOrNull(),
            notes = notes.blankToNull(),
        )

    fun toUpdateRequest() =
        SupplierDto.UpdateRequestDto(
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
            creditorNo = creditorNo.blankToNull(),
            defaultAccountNo = defaultAccountNo.blankToNull(),
            defaultBuCode = defaultBuCode.blankToNull(),
            defaultCostCenter = defaultCostCenter.blankToNull(),
            paymentTermsDays = paymentTermsDays.trim().toIntOrNull(),
            notes = notes.blankToNull(),
            isActive = isActive,
        )
}

private fun String.blankToNull(): String? = trim().takeIf { it.isNotEmpty() }

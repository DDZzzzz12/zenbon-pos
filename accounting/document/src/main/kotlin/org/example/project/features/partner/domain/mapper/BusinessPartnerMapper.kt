package org.example.project.features.partner.domain.mapper

import org.example.project.core.domain.dto.partner.BusinessPartnerDto
import org.example.project.features.debtor.database.tables.DebtorTable
import org.example.project.features.debtor.domain.models.Debtor
import org.example.project.features.partner.domain.models.BusinessPartner
import org.example.project.features.supplier.database.tables.SupplierTable
import org.example.project.features.supplier.domain.models.Supplier
import org.jetbrains.exposed.v1.core.eq

/**
 * Entity → DTO. **Must be called inside a transaction**: the role flags and the IBAN list are lazy
 * Exposed reads.
 */
fun BusinessPartner.toDto(): BusinessPartnerDto.ResponseDto {
    // Bound outside the `find` lambdas: inside them `id` resolves against the expression builder,
    // not against this entity, and the comparison does not compile.
    val partyId = id
    return BusinessPartnerDto.ResponseDto(
        id = partyId.value,
        name = name,
        legalName = legalName,
        vatId = vatId,
        taxNumber = taxNumber,
        street = street,
        zip = zip,
        city = city,
        country = country,
        hasCreditorRole = Supplier.find { SupplierTable.partnerId eq partyId.value }.empty().not(),
        hasDebtorRole = Debtor.find { DebtorTable.partnerId eq partyId.value }.empty().not(),
        knownIbans = bankAccounts.map { it.iban },
    )
}

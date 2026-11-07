package org.example.project.features.debtor.domain.mapper

import org.example.project.core.domain.dto.debtor.DebtorDto
import org.example.project.features.debtor.domain.models.Debtor

/**
 * Entity → wire. **Requires an ambient transaction.**
 *
 * Identity (name, address, tax ids, contact) comes from the party; the debtor-specific fields
 * below it — debtor number, revenue account, e-invoice format — stay on the role, because they
 * describe how we invoice this company, not who it is.
 *
 * [salesInvoiceCount] is passed in rather than read here: the list endpoint counts every debtor's
 * invoices in one query, and a mapper that issued its own count would turn that into N.
 */
fun Debtor.toDto(salesInvoiceCount: Long = 0): DebtorDto.ResponseDto {
    val party = partner
    return DebtorDto.ResponseDto(
        id = id.value,
        name = party.name,
        legalName = party.legalName,
        vatId = party.vatId,
        taxNumber = party.taxNumber,
        street = party.street,
        zip = party.zip,
        city = party.city,
        country = party.country,
        email = party.email,
        phone = party.phone,
        debtorNo = debtorNo,
        paymentTermsDays = paymentTermsDays,
        defaultRevenueAccountNo = defaultRevenueAccountNo,
        defaultBuCode = defaultBuCode,
        isActive = isActive,
        notes = notes,
        salesInvoiceCount = salesInvoiceCount,
        einvoiceFormat = einvoiceFormat,
    )
}

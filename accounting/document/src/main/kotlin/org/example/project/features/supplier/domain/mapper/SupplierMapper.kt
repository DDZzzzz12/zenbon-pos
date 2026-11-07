package org.example.project.features.supplier.domain.mapper

import org.example.project.core.domain.dto.supplier.SupplierDto
import org.example.project.features.partner.domain.models.BusinessPartner
import org.example.project.features.supplier.domain.models.Supplier
import org.example.project.utils.formatter.toDecimalString
import java.math.BigDecimal

/**
 * Entity → DTO. **Must be called inside a transaction**: [BusinessPartner.bankAccounts] is a lazy
 * Exposed relation and throws once the transaction has closed.
 *
 * Identity (name, address, tax ids, contact) is read from the party: one company we both buy from
 * and sell to has a single identity, and the party is where it lives. The creditor role once
 * carried its own copy of those columns; the business-partner model removed them, so the party is
 * now the only place they exist and this join is not optional.
 *
 * [openTotal] and [ytdSpend] are the computed aggregates (see `SupplierRepository.openTotalsFor` /
 * `ytdSpendFor`); they default to zero so a caller that has no aggregate for this supplier still
 * renders "0.00" rather than a blank.
 */
fun Supplier.toDto(
    openTotal: BigDecimal = BigDecimal.ZERO,
    ytdSpend: BigDecimal = BigDecimal.ZERO,
    currency: String = "EUR",
): SupplierDto.ResponseDto {
    val party = partner
    return SupplierDto.ResponseDto(
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
        creditorNo = creditorNo,
        sepaCreditorId = sepaCreditorId,
        defaultAccountNo = defaultAccountNo,
        defaultBuCode = defaultBuCode,
        defaultCostCenter = defaultCostCenter,
        paymentTermsDays = paymentTermsDays,
        isProvisional = isProvisional,
        isActive = isActive,
        notes = notes,
        knownIbans = party.bankAccounts.map { it.iban },
        openTotal = openTotal.toDecimalString(),
        ytdSpend = ytdSpend.toDecimalString(),
        currency = currency,
    )
}

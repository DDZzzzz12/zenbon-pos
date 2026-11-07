package org.example.project.features.kassenbuch.data.service

import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.enums.kassenbuch.KassenbuchLineKind
import org.example.project.core.domain.enums.tse.TseSigningStatus
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.kassenbuch.domain.models.KassenbuchHeader
import org.example.project.features.kassenbuch.domain.repository.KassenbuchRepository
import org.example.project.features.kassenbuch.mapper.toKassenbuchEntryData
import org.example.project.features.kassenbuch.mapper.toLineItemData
import org.example.project.features.setup.domain.models.ShopInfo

/**
 * Writes one fiscal Kassenbuch entry INSIDE the Z-Bon's transaction (F58) — the in-closing tip
 * payouts and the drawer count's Kassendifferenz — PENDING, with its Eigenbeleg linked, returning the
 * payload the closing signs in phase 2 after the commit.
 *
 * The same steps as phase 1 of `KassenbuchServiceImpl.createSignedEntry` (request validation, the
 * same request→entry mappers, PENDING, the mandatory document link, [KassenbuchSigningPayload]) minus
 * what the closing already owns: the advisory lock, the terminal guard and the signing itself.
 */
internal object InClosingEntryWriter {
    data class Written(
        val header: KassenbuchHeader,
        val belegNr: String,
        val payload: KassenbuchSigningPayload.Payload,
    )

    /** Requires the closing's ambient transaction, with `pg_advisory_xact_lock(1)` held. */
    fun write(
        kassenbuchRepository: KassenbuchRepository,
        shop: ShopInfo,
        operator: Employee,
        request: KassenbuchDto.CreateKassenbuchEntryRequestDto,
        belegNr: String,
        systemLineKinds: Set<KassenbuchLineKind> = emptySet(),
    ): Written {
        val errors = request.validate(systemLineKinds)
        check(errors.isEmpty()) { "In-closing Kassenbuch request is invalid: ${errors.first()}" }
        val documentId = checkNotNull(request.documentId) { "In-closing Kassenbuch entry has no Eigenbeleg" }

        val headerData = request.toKassenbuchEntryData(request.lines.first(), belegNr)
        val lineItems = request.lines.map { request.toLineItemData(it) }
        val header = kassenbuchRepository.createEntry(shop, operator, headerData, lineItems)
        check(header.cashBook.isFiscal) { "In-closing Kassenbuch entry landed in non-fiscal cash book ${header.cashBook.id.value}" }
        header.tseSigningStatus = TseSigningStatus.PENDING
        kassenbuchRepository.linkDocument(header.id.value, documentId)
        return Written(header, belegNr, KassenbuchSigningPayload.of(headerData, correctedEntryType = null, lineItems = lineItems))
    }
}

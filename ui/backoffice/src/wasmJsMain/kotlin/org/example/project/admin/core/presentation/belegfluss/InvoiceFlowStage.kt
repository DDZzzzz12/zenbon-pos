package org.example.project.admin.core.presentation.belegfluss

import org.example.project.core.domain.dto.document.InvoiceListDto

/**
 * The four stages of the incoming Belegfluss (Erfassen · Prüfen · Zuordnen · Erledigt).
 *
 * [bucket] is the Rechnungen bucket a stage opens: Zuordnen opens `OFFEN`, whose sub-filters
 * `UEBERFAELLIG` and `UEBERZAHLT` narrow or complement it but belong to the same stage.
 */
enum class InvoiceFlowStage(
    val bucket: InvoiceListDto.Bucket,
) {
    CAPTURE(InvoiceListDto.Bucket.ZU_ERFASSEN),
    REVIEW(InvoiceListDto.Bucket.ZU_PRUEFEN),
    ASSIGN(InvoiceListDto.Bucket.OFFEN),
    DONE(InvoiceListDto.Bucket.FERTIG),
}

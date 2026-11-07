package org.example.project.core.data.remote

import org.example.project.core.domain.dto.bill.BillDto

/** Page size [searchAllReceipts] asks the server for; a short page ends the walk. */
const val BILL_RECEIPTS_PAGE_SIZE: Int = 100

/**
 * Every receipt whose close lies in [startDate]..[endDate] — Stornos, cancelled originals and
 * Schulungsbelege included (`includeDeleted = true`, `includeTraining = true`), because each signed
 * Beleg is a fiscal record a receipt list must show: the Z-Bon's `bill.zbon_id` and its DSFinV-K
 * export both carry the training receipts, and the list omitted them (emu20 NOTE-t). A training
 * receipt arrives with `isTraining = true` for the list to badge. Walks `/search` page by page until
 * a short page, so there is no silent cap: the Z-Bon "Rechnungen" tab used to stop at the default
 * 50 rows (admin16 FAIL-1).
 *
 * A revenue figure computed over the result must use `activeBillsRevenue()`, not a plain sum — it
 * leaves out training receipts as well as Stornos and cancelled originals.
 */
suspend fun BillApiClient.searchAllReceipts(
    startDate: String,
    endDate: String,
    pageSize: Int = BILL_RECEIPTS_PAGE_SIZE,
): List<BillDto.BillResponseDto> {
    require(pageSize > 0) { "pageSize must be positive, was $pageSize" }
    val receipts = mutableListOf<BillDto.BillResponseDto>()
    while (true) {
        val page =
            search(
                startDate = startDate,
                endDate = endDate,
                includeDeleted = true,
                offset = receipts.size,
                limit = pageSize,
                includeTraining = true,
            )
        receipts += page
        if (page.size < pageSize) return receipts
    }
}

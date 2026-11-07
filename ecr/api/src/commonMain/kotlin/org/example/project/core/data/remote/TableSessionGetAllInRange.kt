package org.example.project.core.data.remote

import org.example.project.core.domain.dto.table.TableSessionDto

/** Page size [getAllSessionsInRange] asks the server for; a short page ends the walk. */
const val TABLE_SESSIONS_PAGE_SIZE: Int = 100

/**
 * Every table session that started in [startDate]..[endDate]. Walks `GET /api/tables/sessions`
 * page by page (`limit` + `offset`, the server orders by id) until a short page, so there is no
 * silent cap: the Z-Bon "Sitzungen" tab used to stop at the server's default of 50 rows
 * (admin17 FAIL-7, #146: 50 of 58). Twin of [searchAllReceipts].
 */
suspend fun TableSessionApiClient.getAllSessionsInRange(
    startDate: String,
    endDate: String,
    pageSize: Int = TABLE_SESSIONS_PAGE_SIZE,
): List<TableSessionDto.TableSessionResponseDto> {
    require(pageSize > 0) { "pageSize must be positive, was $pageSize" }
    val sessions = mutableListOf<TableSessionDto.TableSessionResponseDto>()
    while (true) {
        val page =
            getAllSessions(
                limit = pageSize,
                offset = sessions.size,
                startDate = startDate,
                endDate = endDate,
            )
        sessions += page
        if (page.size < pageSize) return sessions
    }
}

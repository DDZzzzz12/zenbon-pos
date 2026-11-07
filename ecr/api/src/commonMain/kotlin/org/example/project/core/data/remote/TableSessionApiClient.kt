package org.example.project.core.data.remote

import org.example.project.core.domain.dto.table.TableSessionDto

interface TableSessionApiClient {
    suspend fun getAllSessions(
        limit: Int? = null,
        offset: Int? = null,
        tableId: Long? = null,
        employeeId: Long? = null,
        completed: Boolean? = null,
        startDate: String? = null,
        endDate: String? = null,
    ): List<TableSessionDto.TableSessionResponseDto>

    suspend fun getSessionById(id: Long): TableSessionDto.TableSessionResponseDto
}

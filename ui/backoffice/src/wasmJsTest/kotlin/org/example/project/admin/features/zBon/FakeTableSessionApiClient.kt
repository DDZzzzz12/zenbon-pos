package org.example.project.admin.features.zBon

import org.example.project.core.data.remote.TableSessionApiClient
import org.example.project.core.domain.dto.table.TableSessionDto

/**
 * [TableSessionApiClient] fake answering [getSessionById] with [session] and [getAllSessions] with
 * [sessions], paged like the server: `limit` defaults to 50, `offset` to 0.
 */
class FakeTableSessionApiClient(
    private val session: TableSessionDto.TableSessionResponseDto? = null,
    private val sessions: List<TableSessionDto.TableSessionResponseDto> = emptyList(),
) : TableSessionApiClient {
    /** (offset, limit) of every [getAllSessions] call, as sent, in call order. */
    val listCalls = mutableListOf<Pair<Int?, Int?>>()

    override suspend fun getAllSessions(
        limit: Int?,
        offset: Int?,
        tableId: Long?,
        employeeId: Long?,
        completed: Boolean?,
        startDate: String?,
        endDate: String?,
    ): List<TableSessionDto.TableSessionResponseDto> {
        listCalls += offset to limit
        return sessions.drop(offset ?: 0).take(limit ?: SERVER_DEFAULT_LIMIT)
    }

    override suspend fun getSessionById(id: Long): TableSessionDto.TableSessionResponseDto =
        session ?: throw IllegalStateException("FakeTableSessionApiClient: no session configured")

    private companion object {
        /** `GET /api/tables/sessions` without `limit` answers at most this many rows. */
        const val SERVER_DEFAULT_LIMIT = 50
    }
}

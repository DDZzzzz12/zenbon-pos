package org.example.project.admin.features.tse

import org.example.project.core.data.remote.TseFindingsApiClient
import org.example.project.core.domain.dto.tse.TseExplanationDto
import org.example.project.core.domain.enums.tse.TseExplanationSource

/**
 * Records what the screen actually sent. [explained] is the point of the fake: a client-side guard
 * is only worth having if the rejected request never reaches the API, and that is invisible from
 * the state alone.
 */
class FakeTseFindingsApiClient : TseFindingsApiClient {
    var response = TseExplanationDto.FindingsResponseDto(emptyList(), emptyList())
    val explained = mutableListOf<TseExplanationDto.CreateRequestDto>()
    var failWith: Throwable? = null

    override suspend fun findings(): TseExplanationDto.FindingsResponseDto {
        failWith?.let { throw it }
        return response
    }

    override suspend fun explain(request: TseExplanationDto.CreateRequestDto): TseExplanationDto.ExplanationDto {
        failWith?.let { throw it }
        explained += request
        return TseExplanationDto.ExplanationDto(
            id = explained.size.toLong(),
            tseSerialHex = request.tseSerialHex,
            tanrFrom = request.tanrFrom,
            tanrTo = request.tanrTo,
            category = request.category,
            text = request.text,
            source = TseExplanationSource.OPERATOR,
            createdAt = "2026-09-22T12:00:00",
        )
    }
}

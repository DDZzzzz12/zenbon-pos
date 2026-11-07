package org.example.project.admin.features.customexport

import org.example.project.core.data.remote.CustomExportApiClient
import org.example.project.core.data.remote.CustomExportRunResult
import org.example.project.core.domain.dto.customexport.CustomExportDto
import org.example.project.core.network.FileDownload

/**
 * Records what the view model asked for and replays canned answers.
 *
 * [saves] is the point of the class: column positions are minted from list order at save time, and
 * only the request that actually went out can prove it.
 */
class FakeCustomExportApiClient : CustomExportApiClient {
    var subjects: List<CustomExportDto.SubjectDto> = emptyList()
    var definitions: List<CustomExportDto.DefinitionDto> = emptyList()

    /** Thrown by every call once set — enough to drive the error paths. */
    var failWith: Throwable? = null

    /** What [run] answers with. */
    var runResult: CustomExportRunResult =
        CustomExportRunResult(
            download = FileDownload(bytes = ByteArray(0), fileName = "export.csv", contentType = "text/csv"),
            rowCount = 0,
            truncated = false,
        )

    val saves = mutableListOf<CustomExportDto.SaveDefinitionRequestDto>()
    val updates = mutableListOf<Pair<Long, CustomExportDto.SaveDefinitionRequestDto>>()
    val deletes = mutableListOf<Long>()
    val runs = mutableListOf<Long>()

    override suspend fun catalogue(): List<CustomExportDto.SubjectDto> {
        failWith?.let { throw it }
        return subjects
    }

    override suspend fun list(): List<CustomExportDto.DefinitionDto> {
        failWith?.let { throw it }
        return definitions
    }

    override suspend fun save(request: CustomExportDto.SaveDefinitionRequestDto): CustomExportDto.DefinitionDto {
        saves += request
        failWith?.let { throw it }
        return CustomExportDto.DefinitionDto(
            id = 1,
            name = request.name,
            subjectKey = request.subjectKey,
            fields = request.fields,
            dateFrom = request.dateFrom,
            dateTo = request.dateTo,
            enabledToggles = request.enabledToggles,
        )
    }

    override suspend fun update(
        id: Long,
        request: CustomExportDto.SaveDefinitionRequestDto,
    ): CustomExportDto.DefinitionDto {
        updates += id to request
        failWith?.let { throw it }
        return CustomExportDto.DefinitionDto(
            id = id,
            name = request.name,
            subjectKey = request.subjectKey,
            fields = request.fields,
            dateFrom = request.dateFrom,
            dateTo = request.dateTo,
            enabledToggles = request.enabledToggles,
        )
    }

    override suspend fun delete(id: Long) {
        deletes += id
        failWith?.let { throw it }
    }

    override suspend fun run(id: Long): CustomExportRunResult {
        runs += id
        failWith?.let { throw it }
        return runResult
    }
}

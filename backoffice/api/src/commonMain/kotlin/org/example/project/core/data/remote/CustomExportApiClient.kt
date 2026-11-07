package org.example.project.core.data.remote

import org.example.project.core.domain.dto.customexport.CustomExportDto

/**
 * Custom export definitions — the fields an operator chose, saved for reuse.
 *
 * Throws-style, like the rest of Stage C: callers wrap in `runCatchingApi { … }`.
 */
interface CustomExportApiClient {
    /** Subjects and fields **as this caller may see them** — the server filters by capability. */
    suspend fun catalogue(): List<CustomExportDto.SubjectDto>

    suspend fun list(): List<CustomExportDto.DefinitionDto>

    suspend fun save(request: CustomExportDto.SaveDefinitionRequestDto): CustomExportDto.DefinitionDto

    /** Replaces the stored definition [id] in place; [save] would store a second, new definition. */
    suspend fun update(
        id: Long,
        request: CustomExportDto.SaveDefinitionRequestDto,
    ): CustomExportDto.DefinitionDto

    suspend fun delete(id: Long)

    /**
     * Runs a definition and returns the CSV together with the run's row count and
     * truncation flag — see [CustomExportRunResult] for why those are not on `FileDownload`.
     */
    suspend fun run(id: Long): CustomExportRunResult
}

package org.example.project.features.customexport.domain.service

import org.example.project.core.domain.dto.customexport.CustomExportDto
import org.example.project.core.domain.enums.auth.Capability

/** Saved custom export definitions. */
interface CustomExportService {
    /** The catalogue as [held] may see it — subjects with no visible field are omitted. */
    suspend fun catalogue(held: Set<Capability>): List<CustomExportDto.SubjectDto>

    /** The shop's saved definitions, each flagged if the catalogue no longer resolves it. */
    suspend fun list(): List<CustomExportDto.DefinitionDto>

    /** One definition, or null when the id is unknown. */
    suspend fun get(id: Long): CustomExportDto.DefinitionDto?

    /**
     * Saves a new definition.
     *
     * Validated against [held]: the server does not trust that the client offered only fields the
     * caller may see.
     */
    suspend fun save(
        request: CustomExportDto.SaveDefinitionRequestDto,
        held: Set<Capability>,
        employeeId: String?,
    ): CustomExportDto.DefinitionDto

    /**
     * Replaces an existing definition in place — same id — with [request].
     *
     * Validated exactly as [save] is, against [held]. Throws a 404 `PosException` when [id] is unknown.
     */
    suspend fun update(
        id: Long,
        request: CustomExportDto.SaveDefinitionRequestDto,
        held: Set<Capability>,
    ): CustomExportDto.DefinitionDto

    /**
     * Runs a saved definition into a CSV.
     *
     * Every constraint the definition declared is applied here, and one it did not: the shop. A
     * subject that names a shop column is always narrowed to the current shop, because that is not
     * the operator's to switch off — an unscoped partner export looks identical in a single-shop
     * install and leaks every other shop's rows the moment there is a second one.
     *
     * Refuses rather than degrades. A definition whose subject or field key the catalogue no longer
     * resolves, or whose columns [held] may not see, produces no file at all: a CSV quietly one
     * column short, or one column of blanks, is worse than no CSV.
     *
     * @param held the **runner's** capabilities, not the author's — definitions are shared and
     *   outlive whoever built them, so the check happens here and not only at save time.
     */
    suspend fun run(
        definitionId: Long,
        held: Set<Capability>,
    ): CustomExportDto.RunResultDto

    /** Deletes a definition. Its fields cascade. */
    suspend fun delete(id: Long)
}

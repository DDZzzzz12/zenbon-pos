package org.example.project.core.data.remote

import org.example.project.core.domain.dto.pfand.PfandTypeDto

/**
 * Throws-style API client for the Pfand (deposit) type catalog, `/api/pfand-types`.
 *
 * Read-only for now: the product form needs the list to attach a Pfand type to a product. The
 * catalog's create/update/deactivate routes exist server-side but have no client screen yet.
 */
interface PfandTypeApiClient {
    /** Pfand types, active only unless [includeInactive]. */
    suspend fun list(includeInactive: Boolean = false): List<PfandTypeDto.PfandTypeResponseDto>
}

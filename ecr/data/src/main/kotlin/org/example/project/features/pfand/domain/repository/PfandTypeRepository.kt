package org.example.project.features.pfand.domain.repository

import org.example.project.features.pfand.domain.models.PfandType
import java.math.BigDecimal

/** Data access for the Pfand (deposit) type catalog. */
interface PfandTypeRepository {
    fun list(includeInactive: Boolean): List<PfandType>

    fun findById(id: Long): PfandType?

    fun findActiveByName(name: String): PfandType?

    fun create(
        name: String,
        depositValue: BigDecimal,
        vatClass: String,
    ): PfandType

    fun update(
        id: Long,
        name: String,
        depositValue: BigDecimal,
        vatClass: String,
        isActive: Boolean,
    ): PfandType?
}

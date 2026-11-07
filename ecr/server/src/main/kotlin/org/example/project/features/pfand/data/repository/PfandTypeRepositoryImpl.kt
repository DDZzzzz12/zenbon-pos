/**
 * Implementation of the Pfand type repository using Exposed ORM.
 *
 * Returns plain entities; the service owns the transaction (dbQuery).
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.pfand.data.repository

import org.example.project.features.pfand.database.tables.PfandTypeTable
import org.example.project.features.pfand.domain.models.PfandType
import org.example.project.features.pfand.domain.repository.PfandTypeRepository
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import java.math.BigDecimal

class PfandTypeRepositoryImpl : PfandTypeRepository {
    override fun list(includeInactive: Boolean): List<PfandType> =
        if (includeInactive) {
            PfandType.all().toList()
        } else {
            PfandType.find { PfandTypeTable.isActive eq true }.toList()
        }

    override fun findById(id: Long): PfandType? = PfandType.findById(id)

    override fun findActiveByName(name: String): PfandType? =
        PfandType.find { (PfandTypeTable.name eq name) and (PfandTypeTable.isActive eq true) }.firstOrNull()

    override fun create(
        name: String,
        depositValue: BigDecimal,
        vatClass: String,
    ): PfandType =
        PfandType.new {
            this.name = name
            this.depositValue = depositValue
            this.vatClass = vatClass
            this.isActive = true
            this.createdAt = currentTimeStamp()
            this.updatedAt = currentTimeStamp()
        }

    override fun update(
        id: Long,
        name: String,
        depositValue: BigDecimal,
        vatClass: String,
        isActive: Boolean,
    ): PfandType? {
        val type = PfandType.findById(id) ?: return null
        type.name = name
        type.depositValue = depositValue
        type.vatClass = vatClass
        type.isActive = isActive
        type.updatedAt = currentTimeStamp()
        return type
    }
}

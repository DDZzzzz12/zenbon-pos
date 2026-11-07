/**
 * Pfand (deposit) type service.
 *
 * Owns the two system-managed backing products per type: creating a type seeds a
 * deposit charge + a deposit return product (ProductType.OTHERS, hidden "PFAND"
 * category); updating reprices/renames them; deactivating deactivates them. The
 * VAT class is validated against the configured Vat rows (spec §2/§4).
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.pfand.data.service

import io.ktor.http.HttpStatusCode
import org.example.project.core.domain.dto.pfand.PfandTypeDto
import org.example.project.core.domain.enums.product.ProductType
import org.example.project.core.error.PosErrorCode
import org.example.project.features.payment.domain.models.Vat
import org.example.project.features.pfand.domain.models.PfandType
import org.example.project.features.pfand.domain.repository.PfandTypeRepository
import org.example.project.features.pfand.domain.service.PfandTypeService
import org.example.project.features.pfand.mapper.toResponse
import org.example.project.features.product.database.tables.ProductCategoryTable
import org.example.project.features.product.database.tables.ProductTable
import org.example.project.features.product.domain.models.Product
import org.example.project.features.product.domain.models.ProductCategory
import org.example.project.features.product.domain.models.SystemCategoryNames
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.dbQuery
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import java.math.BigDecimal

class PfandTypeServiceImpl(
    private val repository: PfandTypeRepository,
) : PfandTypeService {
    override suspend fun list(includeInactive: Boolean): List<PfandTypeDto.PfandTypeResponseDto> =
        dbQuery {
            repository.list(includeInactive).map { it.toResponse() }
        }

    override suspend fun create(request: PfandTypeDto.CreatePfandTypeRequestDto): PfandTypeDto.PfandTypeResponseDto =
        dbQuery {
            val errors = request.validate()
            if (errors.isNotEmpty()) throw badRequest(errors.first())
            requireVatRowExists(request.vatClass)
            val name = request.name.trim()
            if (repository.findActiveByName(name) != null) {
                throw PosException(PosErrorCode.VALIDATION_FAILED, HttpStatusCode.Conflict, "A Pfand type named '$name' already exists")
            }
            val value = request.depositValue.toBigDecimal()
            val type = repository.create(name, value, request.vatClass)
            seedBackingProducts(type)
            type.toResponse()
        }

    override suspend fun update(
        id: Long,
        request: PfandTypeDto.UpdatePfandTypeRequestDto,
    ): PfandTypeDto.PfandTypeResponseDto =
        dbQuery {
            val errors = request.validate()
            if (errors.isNotEmpty()) throw badRequest(errors.first())
            requireVatRowExists(request.vatClass)
            val name = request.name.trim()
            val value = request.depositValue.toBigDecimal()
            val type =
                repository.update(id, name, value, request.vatClass, request.isActive)
                    ?: throw notFound(id)
            // Reprice + rename the backing products; propagate active state.
            backing(type, deposit = true)?.apply {
                price = value
                this.name = "$name Pfand"
                isActive = type.isActive
                updatedAt = currentTimeStamp()
            }
            backing(type, deposit = false)?.apply {
                price = value
                this.name = "$name Rückgabe"
                isActive = type.isActive
                updatedAt = currentTimeStamp()
            }
            type.toResponse()
        }

    override suspend fun deactivate(id: Long): Unit =
        dbQuery {
            val type = repository.findById(id) ?: throw notFound(id)
            type.isActive = false
            type.updatedAt = currentTimeStamp()
            backing(type, deposit = true)?.apply {
                isActive = false
                updatedAt = currentTimeStamp()
            }
            backing(type, deposit = false)?.apply {
                isActive = false
                updatedAt = currentTimeStamp()
            }
        }

    // --- helpers ---

    private fun requireVatRowExists(vatClass: String) {
        if (Vat.findById(vatClass) == null) {
            throw badRequest("VAT class '$vatClass' is not configured for this shop")
        }
    }

    private fun pfandCategory(): ProductCategory =
        ProductCategory.find { ProductCategoryTable.name eq SystemCategoryNames.PFAND }.firstOrNull()
            ?: ProductCategory.new {
                name = SystemCategoryNames.PFAND
                enName = "Deposit"
                chName = "押金"
                description = "System deposit (Pfand) articles"
                isActive = true
                sortPosition = BigDecimal.valueOf(999.98)
                updatedAt = currentTimeStamp()
            }

    private fun backing(
        type: PfandType,
        deposit: Boolean,
    ): Product? =
        Product
            .find {
                (ProductTable.pfandTypeId eq type.id) and
                    (if (deposit) ProductTable.isDeposit eq true else ProductTable.isDepositReturn eq true)
            }.firstOrNull()

    private fun seedBackingProducts(type: PfandType) {
        val cat = pfandCategory()
        Product.new {
            productNumber = "PFAND-${type.id.value}"
            name = "${type.name} Pfand"
            enName = name
            chName = name
            price = type.depositValue
            category = cat
            productType = ProductType.OTHERS
            isDeposit = true
            pfandType = type
            isActive = true
            updatedAt = currentTimeStamp()
        }
        Product.new {
            productNumber = "LEERGUT-${type.id.value}"
            name = "${type.name} Rückgabe"
            enName = name
            chName = name
            // Positive magnitude; the refund sign is applied on the order line (spec §6).
            price = type.depositValue
            category = cat
            productType = ProductType.OTHERS
            isDepositReturn = true
            pfandType = type
            isActive = true
            updatedAt = currentTimeStamp()
        }
    }

    private fun badRequest(msg: String) = PosException(PosErrorCode.VALIDATION_FAILED, HttpStatusCode.BadRequest, msg)

    private fun notFound(id: Long) = PosException(PosErrorCode.RESOURCE_NOT_FOUND, HttpStatusCode.NotFound, "Pfand type $id not found")
}

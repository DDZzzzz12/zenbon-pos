package org.example.project.features.setup.data.repository

import org.example.project.core.domain.dto.setup.VatDto
import org.example.project.features.order.database.tables.OrderItemTable
import org.example.project.features.payment.database.tables.VatTable
import org.example.project.features.payment.domain.models.Vat
import org.example.project.features.setup.domain.repository.VatRepository
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.slf4j.LoggerFactory
import java.math.BigDecimal

/**
 * Implementation of VatRepository using Exposed ORM.
 *
 * Provides concrete implementation of data access operations using
 * Exposed ORM for database interactions. Exceptions propagate to
 * the service layer for Result wrapping.
 */
class VatRepositoryImpl : VatRepository {
    private val logger = LoggerFactory.getLogger(VatRepositoryImpl::class.java)

    override fun findAll(): List<Vat> {
        logger.debug("Retrieving all VAT rates")
        val vats =
            Vat.Companion
                .all()
                .orderBy(VatTable.vatClass to SortOrder.ASC)
                .toList()
        logger.debug("Retrieved ${vats.size} VAT rates")
        return vats
    }

    override fun findById(vatId: String): Vat? {
        logger.debug("Finding VAT rate by ID: $vatId")
        return Vat.Companion.findById(vatId)
    }

    override fun findByVatClass(vatClass: String): Vat? {
        logger.debug("Finding VAT rate by class: $vatClass")
        return Vat.Companion.find { VatTable.vatClass eq vatClass }.singleOrNull()
    }

    override fun findByRateRange(
        minRate: BigDecimal,
        maxRate: BigDecimal,
    ): List<Vat> {
        logger.debug("Finding VAT rates in range: $minRate to $maxRate")
        val vats =
            Vat.Companion
                .find {
                    (VatTable.vatRate greaterEq minRate) and (VatTable.vatRate lessEq maxRate)
                }.orderBy(VatTable.vatRate to SortOrder.ASC)
                .toList()
        logger.debug("Found ${vats.size} VAT rates in range")
        return vats
    }

    override fun setStandardGermanRates(): List<Vat> {
        val standardGermanRates =
            listOf(
                VatDto.CreateVatRequestDto("A", "STANDARD_19", 0.19),
                VatDto.CreateVatRequestDto("B", "REDUCED_7", 0.07),
                VatDto.CreateVatRequestDto("C", "ZERO", 0.0),
            )

        val notExistingVats = mutableListOf<VatDto.CreateVatRequestDto>()

        logger.debug("Checking for existing VAT rates")
        standardGermanRates.forEach { vatDto ->
            Vat.Companion.find { VatTable.id eq vatDto.vatId }.firstOrNull().let { vat ->
                if (vat == null) {
                    logger.debug("VAT rate not found: ${vatDto.vatId}")
                    notExistingVats.add(vatDto)
                }
            }
        }

        if (notExistingVats.isNotEmpty()) {
            logger.debug("Creating ${notExistingVats.size} missing VAT rates")

            notExistingVats.forEach {
                Vat.Companion.new(it.vatId) {
                    this.vatClass = it.vatClass
                    this.vatRate = BigDecimal.valueOf(it.vatRate)
                }
            }
            logger.debug("VAT rates created successfully")
        } else {
            logger.debug("All standard German VAT rates already exist")
        }

        // Return all standard VAT rates
        return standardGermanRates.mapNotNull { vatDto ->
            Vat.Companion.find { VatTable.id eq vatDto.vatId }.firstOrNull()
        }
    }

    override fun create(
        vatId: String,
        vatClass: String,
        vatRate: BigDecimal,
    ): Vat {
        logger.debug("Creating new VAT rate: class=$vatClass, rate=$vatRate")
        val vat =
            Vat.new(vatId) {
                this.vatClass = vatClass
                this.vatRate = vatRate
            }
        logger.info("VAT rate created successfully: ID=${vat.id.value}, class=$vatClass")
        return vat
    }

    override fun update(
        vatId: String,
        vatClass: String?,
        vatRate: BigDecimal?,
    ): Vat? {
        logger.debug("Updating VAT rate: $vatId")
        val vatRecord = Vat.Companion.findById(vatId) ?: return null

        // Update fields if provided
        vatClass?.let { vatRecord.vatClass = it }
        vatRate?.let { vatRecord.vatRate = it }

        logger.info("VAT rate updated successfully: ID=$vatId")
        return vatRecord
    }

    override fun delete(vatId: String): String {
        // First check if VAT can be deleted
        if (!canDeleteSync(vatId)) {
            throw IllegalArgumentException("VAT rate $vatId is referenced by order items and cannot be deleted")
        }

        logger.debug("Deleting VAT rate: $vatId")
        val vat =
            Vat.findById(vatId)
                ?: throw IllegalArgumentException("VAT rate not found: $vatId")

        val entityName = vat.vatClass
        vat.delete()
        logger.info("VAT rate deleted successfully: ID=$vatId")
        return entityName
    }

    /**
     * Synchronous version of canDelete for use within repository methods.
     */
    private fun canDeleteSync(vatId: String): Boolean =
        try {
            val referencedCount = OrderItemTable.selectAll().where { OrderItemTable.vatId eq vatId }.count()
            referencedCount == 0L
        } catch (e: Exception) {
            logger.error("Failed to check VAT deletion safety: $vatId", e)
            false
        }

    override suspend fun exists(vatId: String): Boolean =
        try {
            Vat.Companion.findById(vatId) != null
        } catch (e: Exception) {
            logger.error("Failed to check VAT existence: $vatId", e)
            false
        }

    override suspend fun vatClassExists(vatClass: String): Boolean =
        try {
            Vat.Companion.find { VatTable.vatClass eq vatClass }.count() > 0
        } catch (e: Exception) {
            logger.error("Failed to check VAT class existence: $vatClass", e)
            false
        }

    override suspend fun canDelete(vatId: String): Boolean =
        try {
            // Check if any order items reference this VAT
            val referencedCount = OrderItemTable.selectAll().where { OrderItemTable.vatId eq vatId }.count()
            referencedCount == 0L
        } catch (e: Exception) {
            logger.error("Failed to check VAT deletion safety: $vatId", e)
            false // Safe default - don't allow deletion if we can't check
        }

    override suspend fun count(): Long =
        try {
            logger.debug("Counting all VAT rates")
            VatTable.selectAll().count()
        } catch (e: Exception) {
            logger.error("Failed to count VAT rates", e)
            0L
        }

    override suspend fun countByRateRange(
        minRate: BigDecimal,
        maxRate: BigDecimal,
    ): Long =
        try {
            logger.debug("Counting VAT rates in range: $minRate to $maxRate")
            VatTable
                .selectAll()
                .where {
                    (VatTable.vatRate greaterEq minRate) and (VatTable.vatRate lessEq maxRate)
                }.count()
        } catch (e: Exception) {
            logger.error("Failed to count VAT rates by range", e)
            0L
        }

    override fun createBatch(vatRates: List<Pair<String, BigDecimal>>): List<Vat> {
        logger.debug("Creating ${vatRates.size} VAT rates in batch")

        val createdVats =
            vatRates.map { (vatClass, vatRate) ->
                Vat.Companion.new {
                    this.vatClass = vatClass
                    this.vatRate = vatRate
                }
            }

        logger.info("Created ${createdVats.size} VAT rates in batch")
        return createdVats
    }
}

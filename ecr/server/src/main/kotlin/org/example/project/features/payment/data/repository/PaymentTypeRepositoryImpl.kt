package org.example.project.features.payment.data.repository

import kotlinx.datetime.LocalDateTime
import org.example.project.features.payment.database.tables.PaymentTable
import org.example.project.features.payment.database.tables.PaymentTypeTable
import org.example.project.features.payment.domain.models.PaymentType
import org.example.project.features.payment.domain.repository.PaymentTypeRepository
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.slf4j.LoggerFactory

/**
 * Concrete implementation of PaymentTypeRepository.
 *
 * Implements all PaymentType data access operations using Exposed ORM.
 * Returns plain entities; exceptions propagate naturally to the service layer.
 */
class PaymentTypeRepositoryImpl : PaymentTypeRepository {
    private val logger = LoggerFactory.getLogger(PaymentTypeRepositoryImpl::class.java)

    override fun getAllPaymentTypes(includeInactive: Boolean): List<PaymentType> {
        logger.debug("Retrieving all payment types (includeInactive=$includeInactive)")

        val paymentTypes =
            if (includeInactive) {
                PaymentType.all()
            } else {
                PaymentType.find { PaymentTypeTable.isActive eq true }
            }.orderBy(PaymentTypeTable.name to SortOrder.ASC).toList()

        logger.debug("Found ${paymentTypes.size} payment types")
        return paymentTypes
    }

    override fun getPaymentTypeById(id: Long): PaymentType? {
        logger.debug("Finding payment type by ID: $id")
        val paymentType = PaymentType.Companion.findById(id)
        if (paymentType != null) {
            logger.debug("Payment type found: ${paymentType.name}")
        } else {
            logger.debug("Payment type not found: $id")
        }
        return paymentType
    }

    override fun getPaymentTypeByName(name: String): PaymentType? {
        logger.debug("Finding payment type by name: $name")
        val paymentType = PaymentType.Companion.find { PaymentTypeTable.name eq name }.singleOrNull()
        if (paymentType != null) {
            logger.debug("Payment type found by name: $name")
        } else {
            logger.debug("Payment type not found by name: $name")
        }
        return paymentType
    }

    override fun createPaymentType(
        name: String,
        en: String?,
        ch: String?,
        isCash: Boolean,
        isActive: Boolean,
        createsReceivable: Boolean,
    ): PaymentType {
        logger.debug("Creating payment type: name=$name, en=$en, ch=$ch, isCash=$isCash, isActive=$isActive, createsReceivable=$createsReceivable")

        val timestamp = currentTimeStamp()

        val paymentType =
            PaymentType.Companion.new {
                this.name = name
                this.enName = en
                this.chName = ch
                this.isCash = isCash
                this.isActive = isActive
                this.createsReceivable = createsReceivable
                this.updatedAt = timestamp
            }

        logger.info("Successfully created payment type: $name (ID: ${paymentType.id.value})")
        return paymentType
    }

    override fun updatePaymentType(
        id: Long,
        name: String?,
        en: String?,
        ch: String?,
        isCash: Boolean?,
        isActive: Boolean?,
        createsReceivable: Boolean?,
    ): PaymentType? {
        logger.debug("Updating payment type: id=$id, name=$name, en=$en, ch=$ch, isCash=$isCash, isActive=$isActive")

        val paymentType =
            PaymentType.findById(id) ?: run {
                logger.warn("Payment type not found for update: $id")
                return null
            }

        name?.let { paymentType.name = it }
        en?.let { paymentType.enName = it }
        ch?.let { paymentType.chName = it }
        isCash?.let { paymentType.isCash = it }
        isActive?.let { paymentType.isActive = it }
        createsReceivable?.let { paymentType.createsReceivable = it }
        paymentType.updatedAt = currentTimeStamp()

        logger.info("Successfully updated payment type: ${paymentType.name} (ID: $id)")
        return paymentType
    }

    override fun deletePaymentType(id: Long) {
        logger.debug("Deleting payment type: $id")

        val paymentType =
            PaymentType.findById(id)
                ?: throw NoSuchElementException("Payment type not found: $id")

        val name = paymentType.name
        paymentType.isActive = false

        logger.info("Successfully deactivate payment type: $name (ID: $id)")
    }

    override fun existsByName(
        name: String,
        excludeId: Long?,
    ): Boolean {
        logger.debug("Checking if payment type name exists: $name (excludeId=$excludeId)")

        val exists =
            run {
                val query =
                    if (excludeId != null) {
                        PaymentTypeTable
                            .selectAll()
                            .where { (PaymentTypeTable.name eq name) and (PaymentTypeTable.id neq excludeId) }
                    } else {
                        PaymentTypeTable.selectAll().where { PaymentTypeTable.name eq name }
                    }
                !query.empty()
            }

        logger.debug("Payment type name exists: $exists")
        return exists
    }

    override fun getActivePaymentTypes(): List<PaymentType> {
        logger.debug("Retrieving active payment types")

        val activePaymentTypes =
            PaymentType.Companion
                .find { PaymentTypeTable.isActive eq true }
                .orderBy(PaymentTypeTable.name to SortOrder.ASC)
                .toList()

        logger.debug("Found ${activePaymentTypes.size} active payment types")
        return activePaymentTypes
    }

    override fun countPaymentTypes(activeOnly: Boolean): Long {
        logger.debug("Counting payment types (activeOnly=$activeOnly)")

        val count =
            if (activeOnly) {
                PaymentTypeTable.selectAll().where { PaymentTypeTable.isActive eq true }.count()
            } else {
                PaymentTypeTable.selectAll().count()
            }

        logger.debug("Payment type count: $count")
        return count
    }

    override fun isPaymentTypeInUse(id: Long): Boolean {
        logger.debug("Checking if payment type is in use: $id")

        val inUse = !PaymentTable.selectAll().where { PaymentTable.paymentTypeId eq id }.empty()

        logger.debug("Payment type in use: $inUse")
        return inUse
    }

    override fun getPaymentTypesUpdatedAfter(lastSyncTime: String): List<PaymentType> {
        logger.debug("Getting payment types updated after: $lastSyncTime")

        val paymentTypes =
            PaymentType
                .find {
                    (PaymentTypeTable.updatedAt greater LocalDateTime.parse(lastSyncTime)) and
                        (PaymentTypeTable.isActive eq true)
                }.toList()

        logger.debug("Found ${paymentTypes.size} payment types updated after $lastSyncTime")
        return paymentTypes
    }
}

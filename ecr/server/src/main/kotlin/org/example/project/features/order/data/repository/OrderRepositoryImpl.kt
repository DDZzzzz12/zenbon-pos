/**
 * Implementation of OrderRepository using Exposed ORM.
 *
 * Provides concrete implementation of data access operations using
 * Exposed ORM for database interactions. Returns plain entities;
 * exceptions propagate to the service layer.
 */
package org.example.project.features.order.data.repository

import org.example.project.core.domain.enums.order.OrderStatus
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.order.database.tables.OrderNumberSequenceTable
import org.example.project.features.order.database.tables.OrdersTable
import org.example.project.features.order.domain.models.Orders
import org.example.project.features.order.domain.repository.OrderRepository
import org.example.project.features.table.domain.models.TableSession
import org.example.project.utils.TrainingFilter
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

class OrderRepositoryImpl : OrderRepository {
    private val logger = LoggerFactory.getLogger(OrderRepositoryImpl::class.java)

    override fun findAll(): List<Orders> {
        logger.debug("Retrieving all non-training orders")
        return Orders.find { TrainingFilter.orderNotTraining() }.toList()
    }

    override fun findById(orderId: Long): Orders? {
        logger.debug("Retrieving order with ID: $orderId")
        return Orders.findById(orderId)
    }

    override fun findByOrderNumber(orderNumber: String): Orders? {
        logger.debug("Retrieving order with orderNumber: $orderNumber")
        return Orders.find { OrdersTable.orderNumber eq orderNumber }.singleOrNull()
    }

    override fun findByTableSessionId(tableSessionId: Long): List<Orders> {
        logger.debug("Retrieving orders with tableSessionId: $tableSessionId")
        return Orders.find { OrdersTable.tableSessionId eq tableSessionId }.toList()
    }

    override fun findByEmployeeId(employeeId: UUID): List<Orders> {
        logger.debug("Retrieving orders with employeeNumber: {}", employeeId)
        return Orders.find { OrdersTable.employeeId eq employeeId }.toList()
    }

    override fun create(
        employee: Employee,
        tableSession: TableSession,
    ): Orders {
        val orderNumber = nextOrderNumber()
        logger.debug("Issued orderNumber: $orderNumber")

        return Orders.new {
            this.employee = employee
            this.orderNumber = orderNumber
            this.tableSession = tableSession
            this.orderTime = currentTimeStamp()
            this.orderTotal = BigDecimal.ZERO
            this.orderStatus = OrderStatus.DRAFT
        }
    }

    override fun getLastOrderId(): Long {
        // SQL MAX aggregate — was Orders.all().orderBy(...).limit(1), which
        // materialized and sorted the entire orders table on every call.
        val maxId = OrdersTable.id.max()
        return OrdersTable
            .select(maxId)
            .firstOrNull()
            ?.get(maxId)
            ?.value
            ?: 0L.also {
                logger.debug("No orders found, using default of 0")
            }
    }

    /**
     * Issues the next order number, `ORD-yyyyMMdd-N`.
     *
     * N comes from the single row of [OrderNumberSequenceTable], locked `FOR UPDATE` and
     * incremented inside the caller's transaction: two concurrent issuances serialise on that row,
     * and an order that rolls back returns its number. This replaced `max(t_order.id) + 1`, which two
     * parallel transactions read alike (duplicate `ORD-20261008-7`, one order died with a 500).
     *
     * The row is created on the first issuance, seeded past every number already issued
     * ([firstValueFor]), so an upgraded install continues its numbering. Concurrent creators meet in
     * `INSERT … ON CONFLICT DO NOTHING`; under REPEATABLE_READ the loser gets a serialization
     * failure and `createOrder`'s `withRetryThrows` re-runs it. Requires an ambient transaction.
     */
    private fun nextOrderNumber(): String {
        val issued =
            lockedNextValue() ?: run {
                OrderNumberSequenceTable.insertIgnore {
                    it[prefix] = ORDER_NUMBER_PREFIX
                    it[nextValue] = firstValueFor()
                }
                checkNotNull(lockedNextValue()) { "order_number_sequence row vanished after insert" }
            }
        OrderNumberSequenceTable.update({ OrderNumberSequenceTable.prefix eq ORDER_NUMBER_PREFIX }) {
            it[nextValue] = issued + 1
        }

        val dateString = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))
        return "$ORDER_NUMBER_PREFIX-$dateString-$issued"
    }

    /** The counter's [OrderNumberSequenceTable.nextValue], row-locked until the transaction ends; null before the first order. */
    private fun lockedNextValue(): Long? =
        OrderNumberSequenceTable
            .selectAll()
            .where { OrderNumberSequenceTable.prefix eq ORDER_NUMBER_PREFIX }
            .forUpdate()
            .singleOrNull()
            ?.get(OrderNumberSequenceTable.nextValue)

    /**
     * Where the counter starts: one past the highest N already issued, never below one past the
     * highest order id — the value `max(id) + 1` gave the next order, so an install without a
     * counter row continues exactly where the old scheme stopped.
     */
    private fun firstValueFor(): Long {
        val highestIssued =
            OrdersTable
                .select(OrdersTable.orderNumber)
                .where { OrdersTable.orderNumber like "$ORDER_NUMBER_PREFIX-%" }
                .mapNotNull { it[OrdersTable.orderNumber].substringAfterLast('-').toLongOrNull() }
                .maxOrNull() ?: 0L
        return maxOf(highestIssued, getLastOrderId()) + 1
    }

    private companion object {
        const val ORDER_NUMBER_PREFIX = "ORD"
    }
}

/**
 * Repository interface for order data access operations.
 *
 * Defines the contract for order database operations including CRUD operations,
 * order search, and status management. Returns plain entities or nullable types;
 * exceptions propagate to the service layer.
 */
package org.example.project.features.order.domain.repository

import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.order.domain.models.Orders
import org.example.project.features.table.domain.models.TableSession
import java.util.UUID

interface OrderRepository {
    fun findAll(): List<Orders>

    fun findById(orderId: Long): Orders?

    fun findByOrderNumber(orderNumber: String): Orders?

    fun findByTableSessionId(tableSessionId: Long): List<Orders>

    fun findByEmployeeId(employeeId: UUID): List<Orders>

    fun create(
        employee: Employee,
        tableSession: TableSession,
    ): Orders

    /**
     * Gets the highest/last order ID from the database.
     *
     * @return The highest order ID, or 0 if no orders exist
     */
    fun getLastOrderId(): Long
}

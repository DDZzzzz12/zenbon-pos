package org.example.project.features.order.domain.repository

import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.order.domain.models.Cancellation
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.tse.domain.models.TseTransaction

interface CancellationRepository {
    fun createCancellation(
        employee: Employee,
        orderItem: OrderItem,
        reason: String? = null,
        transaction: TseTransaction?,
        isTraining: Boolean = false,
    ): Cancellation
}

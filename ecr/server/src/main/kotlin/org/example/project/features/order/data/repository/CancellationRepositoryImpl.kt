package org.example.project.features.order.data.repository

import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.order.domain.models.Cancellation
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.order.domain.repository.CancellationRepository
import org.example.project.features.tse.domain.models.TseTransaction
import org.example.project.utils.formatter.currentTimeStamp

class CancellationRepositoryImpl : CancellationRepository {
    override fun createCancellation(
        employee: Employee,
        orderItem: OrderItem,
        reason: String?,
        transaction: TseTransaction?,
        isTraining: Boolean,
    ): Cancellation =
        Cancellation.new {
            this.employeeId = employee
            this.createdAt = currentTimeStamp()
            this.orderItem = orderItem
            this.reason = reason
            this.tseTransaction = transaction
            this.isTraining = isTraining
        }
}

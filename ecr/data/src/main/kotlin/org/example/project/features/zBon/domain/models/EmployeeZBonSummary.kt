package org.example.project.features.zBon.domain.models

import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.zBon.database.tables.EmployeeZBonPaymentBreakdownTable
import org.example.project.features.zBon.database.tables.EmployeeZBonSummaryTable
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.*

class EmployeeZBonSummary(
    id: EntityID<Long>,
) : LongEntity(id) {
    companion object : LongEntityClass<EmployeeZBonSummary>(EmployeeZBonSummaryTable)

    var zBon by ZBon referencedOn EmployeeZBonSummaryTable.zBonId
    var employee by Employee referencedOn EmployeeZBonSummaryTable.employeeId
    var tipAmount by EmployeeZBonSummaryTable.tips
    var totalRevenue by EmployeeZBonSummaryTable.totalRevenue
    var createdAt by EmployeeZBonSummaryTable.createdAt
    val paymentBreakdown by EmployeeZBonPaymentBreakdown referrersOn EmployeeZBonPaymentBreakdownTable.employeeZBonSummaryId
}

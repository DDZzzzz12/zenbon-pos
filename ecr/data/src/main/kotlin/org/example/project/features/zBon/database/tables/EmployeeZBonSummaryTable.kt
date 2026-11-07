package org.example.project.features.zBon.database.tables

import org.example.project.features.employee.database.tables.EmployeeTable
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.datetime.datetime

object EmployeeZBonSummaryTable : LongIdTable("employee_zbon_summary", "id") {
    val zBonId = reference("z_bon_id", ZBonTable)
    val employeeId = reference("employee_id", EmployeeTable)
    val tips = decimal("tips", precision = 19, scale = 4)
    val totalRevenue = decimal("total_revenue", precision = 19, scale = 4)
    val createdAt = datetime("created_at")
}

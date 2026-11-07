package org.example.project.features.timetracking.domain.models

import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.timetracking.database.tables.TimeApprovalTable
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.*
import org.jetbrains.exposed.v1.dao.java.UUIDEntity
import org.jetbrains.exposed.v1.dao.java.UUIDEntityClass
import java.util.UUID

class TimeApproval(
    id: EntityID<UUID>,
) : UUIDEntity(id) {
    companion object : UUIDEntityClass<TimeApproval>(TimeApprovalTable)

    var employee by Employee referencedOn TimeApprovalTable.employeeId
    var approver by Employee optionalReferencedOn TimeApprovalTable.approvedBy
    var periodYear by TimeApprovalTable.periodYear
    var periodMonth by TimeApprovalTable.periodMonth
    var submittedAt by TimeApprovalTable.submittedAt
    var approvedAt by TimeApprovalTable.approvedAt
    var status by TimeApprovalTable.status
    var rejectionReason by TimeApprovalTable.rejectionReason
}

package org.example.project.features.kassenbuch.domain.models

import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.kassenbuch.database.tables.CashBookCloseTable
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.LongEntity
import org.jetbrains.exposed.v1.dao.LongEntityClass

class CashBookClose(
    id: EntityID<Long>,
) : LongEntity(id) {
    companion object : LongEntityClass<CashBookClose>(CashBookCloseTable)

    var cashBook by CashBook referencedOn CashBookCloseTable.cashBookId
    var periodStart by CashBookCloseTable.periodStart
    var periodEnd by CashBookCloseTable.periodEnd
    var bookBalance by CashBookCloseTable.bookBalance
    var countedBalance by CashBookCloseTable.countedBalance
    var difference by CashBookCloseTable.difference
    var differenceHeader by KassenbuchHeader optionalReferencedOn CashBookCloseTable.differenceHeaderId
    var closedBy by Employee referencedOn CashBookCloseTable.closedBy
    var closedAt by CashBookCloseTable.closedAt
    var notes by CashBookCloseTable.notes
}

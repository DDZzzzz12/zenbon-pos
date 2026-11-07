package org.example.project.features.openinghours.domain.models

import org.example.project.features.openinghours.database.tables.OpeningHoursTable
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.LongEntity
import org.jetbrains.exposed.v1.dao.LongEntityClass

class OpeningHours(
    id: EntityID<Long>,
) : LongEntity(id) {
    companion object : LongEntityClass<OpeningHours>(OpeningHoursTable)

    var dayOfWeek by OpeningHoursTable.dayOfWeek
    var openTime by OpeningHoursTable.openTime
    var closeTime by OpeningHoursTable.closeTime
    var isClosed by OpeningHoursTable.isClosed
    var shopId by OpeningHoursTable.shopId
    var createdAt by OpeningHoursTable.createdAt
}

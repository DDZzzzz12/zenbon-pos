package org.example.project.features.openinghours.domain.models

import org.example.project.features.openinghours.database.tables.OpeningHoursOverrideTable
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.LongEntity
import org.jetbrains.exposed.v1.dao.LongEntityClass

class OpeningHoursOverride(
    id: EntityID<Long>,
) : LongEntity(id) {
    companion object : LongEntityClass<OpeningHoursOverride>(OpeningHoursOverrideTable)

    var overrideDate by OpeningHoursOverrideTable.overrideDate
    var openTime by OpeningHoursOverrideTable.openTime
    var closeTime by OpeningHoursOverrideTable.closeTime
    var isClosed by OpeningHoursOverrideTable.isClosed
    var reason by OpeningHoursOverrideTable.reason
    var shopId by OpeningHoursOverrideTable.shopId
    var createdAt by OpeningHoursOverrideTable.createdAt
}

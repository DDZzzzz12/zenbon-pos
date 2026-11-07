package org.example.project.features.zBon.domain.models

import org.example.project.features.zBon.database.tables.ZBonPromotionSummaryTable
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.LongEntity
import org.jetbrains.exposed.v1.dao.LongEntityClass

class ZBonPromotionSummary(
    id: EntityID<Long>,
) : LongEntity(id) {
    companion object : LongEntityClass<ZBonPromotionSummary>(ZBonPromotionSummaryTable)

    var zBon by ZBon referencedOn ZBonPromotionSummaryTable.zBonId
    var promotionId by ZBonPromotionSummaryTable.promotionId
    var promotionName by ZBonPromotionSummaryTable.promotionName
    var discountType by ZBonPromotionSummaryTable.discountType
    var timesApplied by ZBonPromotionSummaryTable.timesApplied
    var totalDeducted by ZBonPromotionSummaryTable.totalDeducted
    var createdAt by ZBonPromotionSummaryTable.createdAt
}

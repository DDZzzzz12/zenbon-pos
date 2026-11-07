package org.example.project.features.zBon.database.tables

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.datetime.datetime

object ZBonPromotionSummaryTable : LongIdTable("zbon_promotion_summary", "id") {
    val zBonId = reference("zbon_id", ZBonTable)
    val promotionId = long("promotion_id")
    val promotionName = varchar("promotion_name", 200)
    val discountType = varchar("discount_type", 20)
    val timesApplied = integer("times_applied").default(0)
    val totalDeducted = decimal("total_deducted", precision = 19, scale = 4).default(java.math.BigDecimal.ZERO)
    val createdAt = datetime("created_at")

    init {
        index("idx_zbon_promotion_zbon_id", false, zBonId)
    }
}

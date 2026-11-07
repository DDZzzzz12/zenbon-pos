package org.example.project.features.openinghours.database.tables

import org.example.project.features.setup.database.tables.ShopInfoTable
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.datetime.date
import org.jetbrains.exposed.v1.datetime.datetime
import org.jetbrains.exposed.v1.datetime.time

object OpeningHoursOverrideTable : LongIdTable("opening_hours_override") {
    val overrideDate = date("override_date")
    val openTime = time("open_time").nullable()
    val closeTime = time("close_time").nullable()
    val isClosed = bool("is_closed").default(false)
    val reason = varchar("reason", 200).nullable()
    val shopId = reference("shop_id", ShopInfoTable)
    val createdAt = datetime("created_at")

    init {
        uniqueIndex(overrideDate, shopId)
    }
}

package org.example.project.features.openinghours.database.tables

import org.example.project.features.setup.database.tables.ShopInfoTable
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.datetime.datetime
import org.jetbrains.exposed.v1.datetime.time

object OpeningHoursTable : LongIdTable("opening_hours") {
    val dayOfWeek = integer("day_of_week")
    val openTime = time("open_time")
    val closeTime = time("close_time")
    val isClosed = bool("is_closed").default(false)
    val shopId = reference("shop_id", ShopInfoTable)
    val createdAt = datetime("created_at")
}

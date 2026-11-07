package org.example.project.features.zBon.domain

import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.zBon.database.tables.ZBonTable
import org.example.project.features.zBon.domain.models.ZBon
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull

/**
 * The single definition of "the Stammdaten belonging to a Kassenabschluss".
 *
 * DSFinV-K Tz. 3.2 (p.23) binds the shop master data TO a Kassenabschluss — „für jeden
 * Kassenabschluss nur einmal gespeichert". A closing must export the identity that applied when it
 * was taken, not whatever `shop_info` says at export time (audit KABS-2).
 *
 * There are exactly two moments at which that identity can still be recorded truthfully:
 *
 *  1. **at closing time** — `ZBonRepositoryImpl.createReport` calls [applyTo] on the new row, and
 *  2. **at edit time** — `ShopInfoServiceImpl.updateShopInfo` calls [freezeUnsnapshottedClosings]
 *     with the PRE-edit values, for closings taken before (1) existed (round-6 C5).
 *
 * Both go through [applyTo] on purpose. Two copies of the field list would drift, and drift here
 * means a closing snapshotted at creation and a closing frozen at edit time carry different
 * columns — i.e. the export silently changes shape depending on which path recorded the row.
 */
object StammdatenSnapshot {
    /**
     * Copies [shop]'s DSFinV-K Stammdaten onto [zBon].
     *
     * Requires an ambient Exposed transaction. Overwrites unconditionally — every caller is
     * responsible for having established that [zBon] carries no snapshot yet.
     */
    fun applyTo(
        zBon: ZBon,
        shop: ShopInfo,
    ) {
        zBon.stammName = shop.shopName
        zBon.stammStrasse = shop.shopAddress
        // German PLZ are 5 digits and shop_info.zip_code is an INTEGER that drops the leading zero
        // (Dresden 01067 -> 1067). Capture the padded form the export emits, so the snapshot is
        // byte-identical to what the live-row fallback produced for the same closing.
        zBon.stammPlz = shop.zipCode.toString().padStart(5, '0')
        zBon.stammOrt = shop.shopCity
        // Raw country as stored; the ISO alpha-3 mapping stays at the export seam so both the
        // snapshot and the fallback path go through the same conversion.
        zBon.stammLand = shop.shopCountry
        // STNR and USTID are ALTERNATIVES (audit STAM-8) — snapshot each on its own, never copy
        // one into the other.
        zBon.stammStnr = shop.shopTaxNumber
        zBon.stammUstId = shop.ustIdNr
    }

    /**
     * Freeze-on-edit (round-6 C5). Stamps [shop]'s CURRENT — i.e. pre-edit — Stammdaten onto every
     * closing of that shop which still carries no snapshot, and returns how many were frozen.
     *
     * Requires an ambient Exposed transaction, and **must be called BEFORE the update is applied**:
     * those closings were exported (or are exportable) under the OLD identity, so the pre-edit
     * values are the truthful ones. Reading [shop] after the update would stamp the new identity
     * onto historical turnover — precisely the defect KABS-2 set out to fix.
     *
     * Why this exists at all: V95 backfills the same values at migration time, but only behind a
     * precondition requiring a `SHOP_INFO` row in `entry_log`. The setup wizard creates the shop
     * through `ShopInfoRepository.create` directly rather than through `ShopInfoServiceImpl`, so on
     * the standard install path that row is never written and the backfill declines for all
     * history. Freezing here makes history correct going forward whether or not it ever ran.
     *
     * **A non-null snapshot is never touched.** Overwriting one would rewrite fiscal data that may
     * already have been handed to a Prüfer; a closing that has one is already correct by
     * definition, and one taken after this edit is snapshotted at creation instead.
     */
    fun freezeUnsnapshottedClosings(shop: ShopInfo): Int {
        val pending =
            ZBon
                .find {
                    (ZBonTable.shopInfoId eq shop.id) and ZBonTable.stammName.isNull()
                }.toList()
        pending.forEach { applyTo(it, shop) }
        return pending.size
    }
}

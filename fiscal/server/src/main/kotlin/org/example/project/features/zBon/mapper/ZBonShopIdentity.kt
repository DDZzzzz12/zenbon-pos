package org.example.project.features.zBon.mapper

/**
 * The shop identity a Z-Bon is rendered under — its JSON, its PDF and its reprint all read it.
 *
 * Built by [shopIdentity] from the closing's own Stammdaten snapshot (DSFinV-K Tz. 3.2, audit
 * KABS-2) when it has one, and only otherwise from the live `shop_info` row.
 */
data class ZBonShopIdentity(
    val name: String,
    val address: String,
    val city: String,
    val zipCode: String,
    val country: String,
    val taxNumber: String,
)

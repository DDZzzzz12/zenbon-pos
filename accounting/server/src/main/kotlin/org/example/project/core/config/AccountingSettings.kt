package org.example.project.core.config

import kotlinx.serialization.json.Json
import org.example.project.core.domain.dto.datev.DatevAccountMappingDto
import org.example.project.core.domain.dto.salesinvoice.ArNumberingConfigDto
import org.example.project.core.utils.datev.DatevAccountMappingDefaults
import org.example.project.features.setup.database.tables.ConfigKey
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("org.example.project.core.config.AccountingSettings")

/**
 * Gets the DATEV account mapping, or null when unconfigured / unparseable
 * (callers then fall back to the legacy per-entry-type default accounts).
 */
suspend fun IShopConfigurationManager.getDatevAccountMapping(): DatevAccountMappingDto.MappingDto? =
    try {
        // The stored blob is decoded verbatim, so a slot added after the operator saved their
        // mapping is a missing MAP KEY that no Kotlin default fills. Backfilled here, on the one
        // read every consumer shares (both Buchungsstapel exports, the XML export, the settings
        // screen), so a shop configured before EMPLOYEE_TIP existed neither fails validation nor
        // meets the 409 on its first tipped day. Read-side only: nothing is written back until
        // the operator saves the mapping, which then stores the slot.
        getDecodedSetting(ConfigKey.DATEV_ACCOUNT_MAPPING, DatevAccountMappingDto.MappingDto.serializer())
            ?.let { DatevAccountMappingDefaults.withBackfilledDefaults(it) }
    } catch (e: Exception) {
        logger.error("Failed to parse DATEV account mapping — legacy fallback stays active", e)
        null
    }

/**
 * Ausgangsrechnung numbering.
 *
 * Falls back rather than returning null, unlike [getDatevAccountMapping]: a null mapping there
 * degrades to a legacy path, while a null here would mean "cannot issue invoices at all". A
 * broken settings row must not stop a shop invoicing under the documented default.
 */
suspend fun IShopConfigurationManager.getArNumbering(): ArNumberingConfigDto {
    val fallback = Json.decodeFromString(ArNumberingConfigDto.serializer(), ConfigKey.AR_NUMBERING.defaultValue)
    return try {
        getDecodedSetting(ConfigKey.AR_NUMBERING, ArNumberingConfigDto.serializer()) ?: fallback
    } catch (e: Exception) {
        logger.error("Failed to parse AR numbering configuration — falling back to the default range", e)
        fallback
    }
}

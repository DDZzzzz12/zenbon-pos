package org.example.project.features.zBon.domain

import kotlinx.serialization.json.Json
import org.example.project.BuildConfig
import org.example.project.core.domain.dto.setup.ShopConfigurationDto
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.features.setup.database.tables.ConfigKey
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.utils.dbQuery
import org.slf4j.LoggerFactory

/**
 * Rewrites `kasseSwVersion` in every shop's `KASSEN_MELDUNG_CONFIG` to the running binary.
 *
 * `KASSEN_MELDUNG_CONFIG` is the live § 146a Kassenmeldung record and must describe the software
 * that is actually booking. Two moments put a foreign value there:
 *
 *  * **server start** after a binary upgrade (`ServerManager`), and
 *  * **a backup restore into a running server** (`BackupRestoreServiceImpl.executeRestore`) — the
 *    archive carries the version of the installation that wrote it. Without the sync the next
 *    Z-Bon froze that value into `stamm_kasse_sw_version` ([KassenStammdatenSnapshot.applyTo]) and
 *    the DSFinV-K `cashregister.csv` named software that never booked the closing (QA emu21
 *    FAIL-D: a 1.0.10 archive restored into 1.1.0 exported `KASSE_SW_VERSION` „1.0.10").
 *
 * One definition for both, so the restore cannot drift from the boot path.
 *
 * Before any write, [KassenStammdatenSnapshot.freezeUnsnapshottedClosings] stamps the CURRENT —
 * pre-rewrite — values onto every closing that still renders through the live fallback, so a
 * historical (or just restored) closing keeps exporting exactly the bytes it exported before.
 */
class KassenSwVersionSync(
    private val configRepository: ConfigurationRepository,
    private val runningVersion: String = BuildConfig.APP_VERSION,
) {
    private val logger = LoggerFactory.getLogger(KassenSwVersionSync::class.java)

    /** Returns how many shops had their Kassenmeldung version rewritten. */
    suspend fun syncToRunningBinary(): Int =
        dbQuery {
            KassenStammdatenSnapshot.freezeUnsnapshottedClosings(newSwVersion = runningVersion)
            ShopInfo.all().toList().count { shop -> rewriteIfStale(shop.id.value) }
        }

    private suspend fun rewriteIfStale(shopId: Long): Boolean {
        val existing = loadConfig(shopId)
        if (existing.kasseSwVersion == runningVersion) return false
        configRepository.setSetting(
            shopId = shopId,
            configKey = ConfigKey.KASSEN_MELDUNG_CONFIG,
            value = Json.encodeToString(existing.copy(kasseSwVersion = runningVersion)),
            description = ConfigKey.KASSEN_MELDUNG_CONFIG.description,
        )
        logger.info("Updated kasseSwVersion to {} for shop {}", runningVersion, shopId)
        return true
    }

    private suspend fun loadConfig(shopId: Long): ShopConfigurationDto.KassenMeldungConfigDto {
        val raw =
            configRepository.getSettingAsString(shopId, ConfigKey.KASSEN_MELDUNG_CONFIG.key)
                ?: return ShopConfigurationDto.KassenMeldungConfigDto()
        return try {
            Json.decodeFromString<ShopConfigurationDto.KassenMeldungConfigDto>(raw)
        } catch (e: Exception) {
            ShopConfigurationDto.KassenMeldungConfigDto()
        }
    }
}

package org.example.project.features.backup.data.service

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.example.project.core.config.IShopConfigurationManager
import org.example.project.core.config.RestoredPathPolicy
import org.example.project.core.domain.dto.setup.RestoredPathsDto
import org.example.project.core.domain.enums.setting.RestoredPathDecision
import org.example.project.core.domain.enums.setting.RestoredPathSetting
import org.example.project.core.error.PosErrorCode
import org.example.project.features.backup.domain.service.RestoredPathReviewService
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.features.setup.database.tables.ConfigKey
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.OsUtils
import org.example.project.utils.dbQuery
import org.slf4j.LoggerFactory

/**
 * Reads and resolves the [ConfigKey.RESTORED_PATHS_REVIEW] row the restore writes.
 *
 * Reads the database rather than the configuration cache: this is the authority the cache is
 * refreshed from, and an admin must see exactly what the pause is based on.
 */
class RestoredPathReviewServiceImpl(
    private val configRepository: ConfigurationRepository,
    private val configManager: IShopConfigurationManager,
    private val appDataDirectory: () -> String = { OsUtils.getAppDataDirectory() },
    private val shopId: Long = 1L,
) : RestoredPathReviewService {
    private val logger = LoggerFactory.getLogger(RestoredPathReviewServiceImpl::class.java)

    override suspend fun getReview(): RestoredPathsDto.ReviewDto = reviewOf(dbQuery { readPending() })

    override suspend fun resolve(request: RestoredPathsDto.ResolveRequestDto): RestoredPathsDto.ReviewDto {
        try {
            request.validate()
        } catch (e: IllegalArgumentException) {
            throw PosException(PosErrorCode.VALIDATION_FAILED, HttpStatusCode.BadRequest, e.message ?: "invalid request")
        }
        val appDataDir = appDataDirectory()
        val remaining =
            dbQuery {
                val pending = readPending()
                val applicable = request.decisions.filter { it.setting in pending }
                if (applicable.isEmpty()) {
                    logger.info("resolve: nothing pending among {} — no change", request.decisions.map { it.setting })
                    return@dbQuery pending
                }
                applicable
                    .filter { it.decision == RestoredPathDecision.USE_DEFAULT }
                    .forEach { writeDefault(it.setting, appDataDir) }
                val rest = pending - applicable.map { it.setting }.toSet()
                configRepository.setSetting(shopId, ConfigKey.RESTORED_PATHS_REVIEW, RestoredPathPolicy.encodeReview(rest))
                logger.info(
                    "resolve: restored paths decided {} (ignored, not pending: {}); still pending: {}",
                    applicable.associate { it.setting to it.decision },
                    request.decisions.map { it.setting } - pending.keys,
                    rest.keys,
                )
                rest
            }
        // The pause is read through the configuration cache; reload it so the writers resume now.
        configManager.refreshConfiguration()
        if (remaining.isEmpty()) logger.info("resolve: every restored path reviewed — file writers resume")
        return reviewOf(remaining)
    }

    override suspend fun onPathConfigured(
        setting: RestoredPathSetting,
        configuredPath: String,
    ) {
        val appDataDir = appDataDirectory()
        val changed =
            dbQuery {
                val pending = readPending()
                val updated = RestoredPathPolicy.afterConfigured(pending, setting, configuredPath, appDataDir)
                if (updated == pending) return@dbQuery false
                configRepository.setSetting(shopId, ConfigKey.RESTORED_PATHS_REVIEW, RestoredPathPolicy.encodeReview(updated))
                if (setting in updated) {
                    logger.info("onPathConfigured: {} set to another foreign path '{}' — still awaits review", setting, configuredPath)
                } else {
                    logger.info("onPathConfigured: {} set to '{}' of this installation — review entry resolved", setting, configuredPath)
                }
                true
            }
        if (changed) configManager.refreshConfiguration()
    }

    private suspend fun readPending(): Map<RestoredPathSetting, String> =
        RestoredPathPolicy.decodeReview(configRepository.getSetting(shopId, ConfigKey.RESTORED_PATHS_REVIEW.key))

    /** Replaces only the path field of the setting's config object; every other field is kept. */
    private suspend fun writeDefault(
        setting: RestoredPathSetting,
        appDataDir: String,
    ) {
        val key = RestoredPathPolicy.configKeyOf(setting)
        val existing = configRepository.getSetting(shopId, key.key) as? JsonObject ?: JsonObject(emptyMap())
        val newValue = RestoredPathPolicy.storedDefault(setting, appDataDir)
        val updated = JsonObject(existing + (RestoredPathPolicy.fieldOf(setting) to JsonPrimitive(newValue)))
        configRepository.setSetting(shopId, key, updated)
        logger.info("writeDefault: {} switched from the restored path to '{}'", setting, newValue)
    }

    private fun reviewOf(pending: Map<RestoredPathSetting, String>): RestoredPathsDto.ReviewDto =
        RestoredPathsDto.ReviewDto(
            pending = pending.isNotEmpty(),
            paths = RestoredPathPolicy.toDtos(pending, appDataDirectory()),
        )
}

package org.example.project.features.backup

import kotlinx.coroutines.runBlocking
import org.example.project.core.config.IShopConfigurationManager
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.core.domain.dto.backup.BackupRestoreDto
import org.example.project.core.domain.enums.setting.RestoredPathSetting
import org.example.project.core.error.PosErrorCode
import org.example.project.features.backup.data.service.BackupRestoreServiceImpl
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.infrastructure.errors.PosException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A server-side backup into the restored backup folder of another installation is refused while that
 * folder awaits review — the explicit-operator counterpart of the scheduler skipping the backup job.
 * The refusal comes before the database is touched, so no database is needed here.
 */
@DisplayName("Backup to server refuses a restored backup folder awaiting review")
class RestoredBackupPathRefusalTest {
    private val originalBackups: File = Files.createTempDirectory("original-shop-backups").toFile()

    private val configManager =
        object : IShopConfigurationManager by ShopConfigurationManager {
            override suspend fun getDefaultBackupPath(): String? = originalBackups.absolutePath

            override suspend fun getRestoredPathsPendingReview(): Map<RestoredPathSetting, String> =
                mapOf(RestoredPathSetting.BACKUP_PATH to originalBackups.absolutePath)
        }

    private val service =
        BackupRestoreServiceImpl(
            ConfigurationRepository(),
            configManager,
            org.example.project.database
                .LiveDataSourceProvider {
                    org.example.project.database.DatabaseFactory
                        .getDataSource()
                },
            { "/unused-app-data" },
            postRestoreHooks = emptyList(),
        ) {
            error("seeder is not used here")
        }

    @AfterEach
    fun cleanUp() {
        originalBackups.deleteRecursively()
    }

    @Test
    fun `the default target and a folder beneath it are refused with 409 and nothing is written`() {
        listOf(null, File(originalBackups, "nightly").absolutePath).forEach { savePath ->
            val refusal =
                assertFailsWith<PosException>("savePath=$savePath") {
                    runBlocking { service.createBackupToServer(BackupRestoreDto.CreateBackupToServerRequestDto(savePath = savePath)) }
                }
            assertEquals(PosErrorCode.RESTORED_PATHS_NEED_REVIEW, refusal.code)
            assertEquals(409, refusal.httpStatus.value)
        }
        assertTrue(originalBackups.listFiles().orEmpty().isEmpty())
    }
}

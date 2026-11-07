package org.example.project.features.backup

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.example.project.core.config.IShopConfigurationManager
import org.example.project.core.config.RestoredPathPolicy
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.core.domain.dto.setup.RestoredPathsDto
import org.example.project.core.domain.enums.setting.RestoredPathDecision
import org.example.project.core.domain.enums.setting.RestoredPathSetting
import org.example.project.core.error.PosErrorCode
import org.example.project.features.backup.data.service.RestoredPathReviewServiceImpl
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.features.setup.database.tables.ConfigKey
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.example.project.utils.dbQuery
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The admin's half of the post-restore pause: "Beibehalten" keeps a restored path, "Standard
 * verwenden" rewrites it to this installation's default, and the pause lifts once every flagged path
 * is decided — and only then.
 */
@DisplayName("RestoredPathReviewService — keep or use default, then resume")
class RestoredPathReviewServiceTest {
    private val repository = ConfigurationRepository()
    private val appData: String = Files.createTempDirectory("review-appdata").toFile().absolutePath
    private val foreignDatev = "/Users/original-shop/Zenbon POS/exports"
    private val foreignBackup = "/Volumes/original-nas/backups"
    private var refreshes = 0
    private var shopId = 0L

    private val configManager =
        object : IShopConfigurationManager by ShopConfigurationManager {
            override suspend fun refreshConfiguration(): Boolean {
                refreshes++
                return true
            }
        }

    private lateinit var service: RestoredPathReviewServiceImpl

    @BeforeEach
    fun setUp() {
        TestDatabase.setup()
        shopId = TestData.createShopInfo().id.value
        service = RestoredPathReviewServiceImpl(repository, configManager, { appData }, shopId)
        runBlocking {
            dbQuery {
                repository.setSetting(
                    shopId,
                    ConfigKey.DATEV_XML_CONFIG,
                    """{"enabled":true,"kassenName":"Kasse","exportPath":"$foreignDatev","consultantNumber":"1234"}""",
                )
                repository.setSetting(
                    shopId,
                    ConfigKey.BACKUP_CONFIG,
                    """{"defaultBackupPath":"$foreignBackup","autoEnabled":true,"retentionCount":9}""",
                )
                repository.setSetting(
                    shopId,
                    ConfigKey.RESTORED_PATHS_REVIEW,
                    RestoredPathPolicy.encodeReview(
                        mapOf(
                            RestoredPathSetting.DATEV_EXPORT_PATH to foreignDatev,
                            RestoredPathSetting.BACKUP_PATH to foreignBackup,
                        ),
                    ),
                )
            }
        }
    }

    @AfterEach
    fun tearDown() {
        TestDatabase.teardown()
    }

    @Test
    fun `the review lists each flagged path with this installation's default`() {
        val review = runBlocking { service.getReview() }

        assertTrue(review.pending)
        assertEquals(
            listOf(
                RestoredPathsDto.PathDto(RestoredPathSetting.DATEV_EXPORT_PATH, foreignDatev, File(appData, "exports").path),
                RestoredPathsDto.PathDto(RestoredPathSetting.BACKUP_PATH, foreignBackup, File(appData, "backups").path),
            ),
            review.paths,
        )
    }

    @Test
    fun `use default rewrites only the path field, keep keeps it, and the pause lifts when both are decided`() {
        val partial =
            runBlocking {
                service.resolve(request(RestoredPathSetting.DATEV_EXPORT_PATH to RestoredPathDecision.USE_DEFAULT))
            }
        assertTrue(partial.pending, "one decision of two must not lift the pause")
        assertEquals(listOf(RestoredPathSetting.BACKUP_PATH), partial.paths.map { it.setting })

        val done =
            runBlocking { service.resolve(request(RestoredPathSetting.BACKUP_PATH to RestoredPathDecision.KEEP)) }

        assertFalse(done.pending)
        assertTrue(done.paths.isEmpty())
        val datev = setting(ConfigKey.DATEV_XML_CONFIG)
        assertEquals(File(appData, "exports").path, (datev["exportPath"] as JsonPrimitive).content)
        assertEquals("1234", (datev["consultantNumber"] as JsonPrimitive).content, "other DATEV fields are kept")
        val backup = setting(ConfigKey.BACKUP_CONFIG)
        assertEquals(foreignBackup, (backup["defaultBackupPath"] as JsonPrimitive).content, "KEEP leaves the path")
        assertEquals("9", (backup["retentionCount"] as JsonPrimitive).content)
        assertTrue(RestoredPathPolicy.decodeReview(runBlocking { dbQuery { repository.getSetting(shopId, ConfigKey.RESTORED_PATHS_REVIEW.key) } }).isEmpty())
        assertEquals(2, refreshes, "the cache the writers read must be reloaded after every decision")
    }

    @Test
    fun `a decision for a setting that is not pending changes nothing`() {
        runBlocking {
            dbQuery { repository.setSetting(shopId, ConfigKey.DOCUMENT_STORAGE_CONFIG, """{"path":"/somewhere/else"}""") }
            service.resolve(request(RestoredPathSetting.DOCUMENT_STORAGE_PATH to RestoredPathDecision.USE_DEFAULT))
        }

        assertEquals("/somewhere/else", (setting(ConfigKey.DOCUMENT_STORAGE_CONFIG)["path"] as JsonPrimitive).content)
        assertTrue(runBlocking { service.getReview() }.pending)
    }

    @Test
    fun `an empty request or a setting decided twice is a 400`() {
        listOf(
            RestoredPathsDto.ResolveRequestDto(emptyList()),
            request(
                RestoredPathSetting.BACKUP_PATH to RestoredPathDecision.KEEP,
                RestoredPathSetting.BACKUP_PATH to RestoredPathDecision.USE_DEFAULT,
            ),
        ).forEach { bad ->
            val refusal = assertFailsWith<PosException> { runBlocking { service.resolve(bad) } }
            assertEquals(PosErrorCode.VALIDATION_FAILED, refusal.code)
        }
        assertTrue(runBlocking { service.getReview() }.pending)
    }

    @Test
    fun `the document path offers only use default - KEEP is a 400, USE_DEFAULT stores it blank`() {
        val foreignDocs = "/Users/original-shop/Zenbon POS/documents"
        runBlocking {
            dbQuery {
                repository.setSetting(shopId, ConfigKey.DOCUMENT_STORAGE_CONFIG, """{"path":"$foreignDocs","maxUploadSizeMb":20}""")
                repository.setSetting(
                    shopId,
                    ConfigKey.RESTORED_PATHS_REVIEW,
                    RestoredPathPolicy.encodeReview(mapOf(RestoredPathSetting.DOCUMENT_STORAGE_PATH to foreignDocs)),
                )
            }
        }
        val listed = runBlocking { service.getReview() }.paths.single()
        assertFalse(listed.keepAllowed, "the GET must tell the client KEEP is not offered")

        val refusal =
            assertFailsWith<PosException> {
                runBlocking { service.resolve(request(RestoredPathSetting.DOCUMENT_STORAGE_PATH to RestoredPathDecision.KEEP)) }
            }
        assertEquals(PosErrorCode.VALIDATION_FAILED, refusal.code)
        assertEquals(400, refusal.httpStatus.value)
        assertTrue(refusal.message.orEmpty().contains("USE_DEFAULT"), refusal.message)
        assertEquals(foreignDocs, (setting(ConfigKey.DOCUMENT_STORAGE_CONFIG)["path"] as JsonPrimitive).content, "KEEP changed nothing")
        assertTrue(runBlocking { service.getReview() }.pending)

        val done =
            runBlocking { service.resolve(request(RestoredPathSetting.DOCUMENT_STORAGE_PATH to RestoredPathDecision.USE_DEFAULT)) }

        assertFalse(done.pending)
        val docs = setting(ConfigKey.DOCUMENT_STORAGE_CONFIG)
        assertEquals("", (docs["path"] as JsonPrimitive).content, "blank = this installation's default folder")
        assertEquals("20", (docs["maxUploadSizeMb"] as JsonPrimitive).content)
    }

    @Test
    fun `storing a path of this installation through the config resolves only that entry`() {
        refreshes = 0
        runBlocking { service.onPathConfigured(RestoredPathSetting.BACKUP_PATH, File(appData, "my-backups").path) }

        val review = runBlocking { service.getReview() }
        assertEquals(listOf(RestoredPathSetting.DATEV_EXPORT_PATH), review.paths.map { it.setting })
        assertEquals(1, refreshes, "the writers' pause is read from the cache — it must be reloaded")

        runBlocking { service.onPathConfigured(RestoredPathSetting.DATEV_EXPORT_PATH, "") }
        assertFalse(runBlocking { service.getReview() }.pending, "blank = this installation's default")
    }

    @Test
    fun `another foreign path stays pending but the review shows the configured one`() {
        val nas = "/Volumes/new-nas/backups"
        runBlocking { service.onPathConfigured(RestoredPathSetting.BACKUP_PATH, nas) }

        val review = runBlocking { service.getReview() }
        assertTrue(review.pending)
        assertEquals(nas, review.paths.single { it.setting == RestoredPathSetting.BACKUP_PATH }.configuredPath)
        val done = runBlocking { service.resolve(request(RestoredPathSetting.BACKUP_PATH to RestoredPathDecision.KEEP)) }
        assertEquals(listOf(RestoredPathSetting.DATEV_EXPORT_PATH), done.paths.map { it.setting }, "the POST decision still works")
    }

    @Test
    fun `re-storing the path under review, or a setting not under review, changes nothing`() {
        refreshes = 0
        runBlocking {
            service.onPathConfigured(RestoredPathSetting.BACKUP_PATH, foreignBackup)
            service.onPathConfigured(RestoredPathSetting.DOCUMENT_STORAGE_PATH, File(appData, "docs").path)
        }

        assertEquals(2, runBlocking { service.getReview() }.paths.size)
        assertEquals(0, refreshes)
    }

    private fun request(vararg decisions: Pair<RestoredPathSetting, RestoredPathDecision>) =
        RestoredPathsDto.ResolveRequestDto(decisions.map { (s, d) -> RestoredPathsDto.DecisionDto(s, d) })

    private fun setting(key: ConfigKey): JsonObject =
        Json.parseToJsonElement(runBlocking { dbQuery { repository.getSetting(shopId, key.key) } }.toString()) as JsonObject
}

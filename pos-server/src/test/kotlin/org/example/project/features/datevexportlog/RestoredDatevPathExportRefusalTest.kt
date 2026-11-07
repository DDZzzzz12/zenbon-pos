package org.example.project.features.datevexportlog

import kotlinx.coroutines.runBlocking
import org.example.project.core.config.IShopConfigurationManager
import org.example.project.core.config.restoredPathNeedsReview
import org.example.project.core.domain.dto.datevexportlog.DatevExportLogDto
import org.example.project.core.domain.enums.setting.DatevExportCadence
import org.example.project.core.domain.enums.setting.DatevExportSource
import org.example.project.core.domain.enums.setting.RestoredPathSetting
import org.example.project.core.error.PosErrorCode
import org.example.project.features.datevexportlog.data.service.DatevExportCatchupServiceImpl
import org.example.project.features.datevexportlog.domain.models.DatevExportType
import org.example.project.features.datevexportlog.domain.repository.DatevExportLogRepository
import org.example.project.features.kassenbuch.data.service.DatevApExportServiceImpl
import org.example.project.features.kassenbuch.data.service.DatevBuchungsstapelBelegeExportServiceImpl
import org.example.project.features.kassenbuch.data.service.DatevBuchungsstapelExportServiceImpl
import org.example.project.features.kassenbuch.data.service.DatevXmlExportServiceImpl
import org.example.project.features.kassenbuch.data.service.KassenbuchBelegResolver
import org.example.project.features.kassenbuch.domain.service.DatevXmlExportService
import org.example.project.features.tse.verification.TseExportDirectory
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TestDatabase
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The explicit-export half of the post-restore pause: every export that writes a server copy into
 * the DATEV export folder — and the TSE export folder beneath it — answers 409
 * `RESTORED_PATHS_NEED_REVIEW` while that folder, restored from another installation, awaits review.
 *
 * Every collaborator except the configuration is a proxy that fails on use, so a refusal here proves
 * the guard fires **first**: before any data is read and before anything touches the folder.
 */
@DisplayName("DATEV / TSE exports refuse a restored export folder awaiting review")
class RestoredDatevPathExportRefusalTest {
    private val foreignFolder: File = Files.createTempDirectory("original-shop-exports").toFile()
    private val userId = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        TestDatabase.setup() // the catch-up reads its cursor inside dbQuery
    }

    @AfterEach
    fun cleanUp() {
        TestDatabase.teardown()
        foreignFolder.deleteRecursively()
    }

    /** Only the review state is answered; any other configuration read means the guard came too late. */
    private val pausedConfig: IShopConfigurationManager =
        Proxy.newProxyInstance(
            IShopConfigurationManager::class.java.classLoader,
            arrayOf(IShopConfigurationManager::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getRestoredPathsPendingReview" -> mapOf(RestoredPathSetting.DATEV_EXPORT_PATH to foreignFolder.path)
                "getDatevXmlExportPath" -> foreignFolder.path
                else -> error("configuration read before the restored-path guard: ${method.name}")
            }
        } as IShopConfigurationManager

    private inline fun <reified T : Any> unused(): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            error("${T::class.simpleName}.${method.name} must not be reached")
        } as T

    @Test
    fun `all four DATEV exports that save a server copy answer 409 and write nothing`() {
        val exports: List<Pair<String, suspend () -> DatevExportLogDto>> =
            listOf(
                "Kassenbuch XML" to {
                    DatevXmlExportServiceImpl(unused(), unused(), unused(), pausedConfig, unused(), unused())
                        .export("2026-08-01", "2026-08-31", userId, "2026-08", DatevExportSource.AUTO, null)
                },
                "AP ledger" to {
                    DatevApExportServiceImpl(unused(), unused(), pausedConfig, unused(), unused())
                        .export("2026-08-01", "2026-08-31", userId, "2026-08", DatevExportSource.MANUAL, true)
                },
                "Buchungsstapel" to {
                    DatevBuchungsstapelExportServiceImpl(unused(), unused(), pausedConfig, unused())
                        .export("2026-08-01", "2026-08-31", userId, "2026-08", DatevExportSource.MANUAL, null)
                },
                "Buchungsstapel mit Belegen" to {
                    DatevBuchungsstapelBelegeExportServiceImpl(
                        unused(),
                        unused(),
                        pausedConfig,
                        unused(),
                        KassenbuchBelegResolver(unused()),
                    ).export("2026-08-01", "2026-08-31", userId, "2026-08", DatevExportSource.MANUAL, null)
                },
            )

        exports.forEach { (name, export) ->
            val refusal = assertFailsWith<PosException>(name) { runBlocking { export() } }
            assertEquals(PosErrorCode.RESTORED_PATHS_NEED_REVIEW, refusal.code, name)
            assertEquals(409, refusal.httpStatus.value, name)
        }
        assertTrue(foreignFolder.listFiles().orEmpty().isEmpty(), "nothing may land in the original shop's folder")
    }

    @Test
    fun `the TSE export folder under the paused path is neither created nor handed out`() {
        val refusal = assertFailsWith<PosException> { runBlocking { TseExportDirectory(pausedConfig).resolve() } }

        assertEquals(PosErrorCode.RESTORED_PATHS_NEED_REVIEW, refusal.code)
        assertTrue(!File(foreignFolder, "tse").exists(), "resolve() must not mkdirs into the paused folder")
    }

    @Test
    fun `a manual catch-up answers 409 instead of a 200 listing every period as failed`() {
        val pausedXml =
            object : DatevXmlExportService by unused() {
                override suspend fun export(
                    startDate: String,
                    endDate: String,
                    exportedByUserId: UUID,
                    periodLabel: String,
                    source: DatevExportSource,
                ): DatevExportLogDto = throw restoredPathNeedsReview(RestoredPathSetting.DATEV_EXPORT_PATH, foreignFolder.path, "test")
            }
        val noCursor =
            object : DatevExportLogRepository by unused() {
                override suspend fun findLatestEnd(
                    type: DatevExportType,
                    sourceFilter: DatevExportSource?,
                ): kotlinx.datetime.LocalDateTime? = null
            }
        val catchup = DatevExportCatchupServiceImpl(noCursor, pausedXml, unused(), unused())

        val refusal =
            assertFailsWith<PosException> {
                runBlocking { catchup.execute(DatevExportType.KASSENBUCH, DatevExportCadence.MONTHLY, userId) }
            }
        assertEquals(PosErrorCode.RESTORED_PATHS_NEED_REVIEW, refusal.code)
    }
}

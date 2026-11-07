package org.example.project.features.datevexportlog

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import org.example.project.core.domain.dto.datevexportlog.DatevExportLogDto
import org.example.project.core.domain.enums.setting.DatevExportCadence
import org.example.project.core.domain.enums.setting.DatevExportSource
import org.example.project.core.error.PosErrorCode
import org.example.project.features.datevexportlog.data.repository.DatevExportLogRepositoryImpl
import org.example.project.features.datevexportlog.data.service.DatevExportCatchupServiceImpl
import org.example.project.features.datevexportlog.domain.models.DatevExportType
import org.example.project.features.kassenbuch.domain.service.DatevApExportService
import org.example.project.features.kassenbuch.domain.service.DatevBuchungsstapelExportService
import org.example.project.features.kassenbuch.domain.service.DatevXmlExportService
import org.example.project.features.kassenbuch.domain.service.TransientExportResult
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TestDatabase
import org.example.project.utils.dbQuery
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
class DatevExportCatchupServiceTest {
    private lateinit var repo: DatevExportLogRepositoryImpl

    @BeforeEach
    fun setup() {
        TestDatabase.setup()
        repo = DatevExportLogRepositoryImpl()
    }

    @AfterEach
    fun teardown() {
        TestDatabase.teardown()
    }

    // -------------------------------------------------------------------------
    // Stubs
    // -------------------------------------------------------------------------

    private fun successStub(): DatevXmlExportService =
        object : DatevXmlExportService {
            override suspend fun export(
                startDate: String,
                endDate: String,
                exportedByUserId: UUID,
                periodLabel: String,
                source: DatevExportSource,
            ): DatevExportLogDto = dummyDto(startDate, endDate, exportedByUserId)

            override suspend fun export(
                startDate: String,
                endDate: String,
                exportedByUserId: UUID,
                periodLabel: String,
                source: DatevExportSource,
                cashBookIds: List<Long>?,
            ): DatevExportLogDto = dummyDto(startDate, endDate, exportedByUserId)

            override suspend fun exportTransient(
                startDate: String,
                endDate: String,
                exportedByUserId: UUID,
            ): TransientExportResult = error("not used in this test")

            override suspend fun exportTransient(
                startDate: String,
                endDate: String,
                exportedByUserId: UUID,
                cashBookIds: List<Long>?,
            ): TransientExportResult = error("not used in this test")

            override suspend fun getStatistics(
                startDate: String,
                endDate: String,
                cashBookIds: List<Long>?,
            ): org.example.project.core.domain.dto.export.DatevExportStatisticsDto = error("not used in this test")
        }

    /**
     * A configurable stub whose [onExport] lambda can be swapped per test case.
     * Lambda may throw to simulate a failure.
     */
    private class ConfigurableXmlStub(
        var onExport: (String, String, UUID, String) -> DatevExportLogDto = { s, e, u, _ ->
            dummyDto(s, e, u)
        },
    ) : DatevXmlExportService {
        override suspend fun export(
            startDate: String,
            endDate: String,
            exportedByUserId: UUID,
            periodLabel: String,
            source: DatevExportSource,
        ): DatevExportLogDto = onExport(startDate, endDate, exportedByUserId, periodLabel)

        override suspend fun export(
            startDate: String,
            endDate: String,
            exportedByUserId: UUID,
            periodLabel: String,
            source: DatevExportSource,
            cashBookIds: List<Long>?,
        ): DatevExportLogDto = onExport(startDate, endDate, exportedByUserId, periodLabel)

        override suspend fun exportTransient(
            startDate: String,
            endDate: String,
            exportedByUserId: UUID,
        ): TransientExportResult = error("not used in this test")

        override suspend fun exportTransient(
            startDate: String,
            endDate: String,
            exportedByUserId: UUID,
            cashBookIds: List<Long>?,
        ): TransientExportResult = error("not used in this test")

        override suspend fun getStatistics(
            startDate: String,
            endDate: String,
            cashBookIds: List<Long>?,
        ): org.example.project.core.domain.dto.export.DatevExportStatisticsDto = error("not used in this test")
    }

    private fun stubApExport(): DatevApExportService =
        object : DatevApExportService {
            override suspend fun export(
                startDate: String,
                endDate: String,
                exportedByUserId: UUID,
                periodLabel: String,
                source: DatevExportSource,
            ): DatevExportLogDto = dummyDto(startDate, endDate, exportedByUserId)

            override suspend fun exportTransient(
                startDate: String,
                endDate: String,
                exportedByUserId: UUID,
            ): TransientExportResult = error("not used in this test")

            override suspend fun export(
                startDate: String,
                endDate: String,
                exportedByUserId: UUID,
                periodLabel: String,
                source: DatevExportSource,
                excludeKassenbuchLinked: Boolean,
            ): DatevExportLogDto = dummyDto(startDate, endDate, exportedByUserId)

            override suspend fun exportTransient(
                startDate: String,
                endDate: String,
                exportedByUserId: UUID,
                excludeKassenbuchLinked: Boolean,
            ): TransientExportResult = error("not used in this test")

            override suspend fun getApExportStatistics(
                startDate: String,
                endDate: String,
                excludeKassenbuchLinked: Boolean,
            ): org.example.project.core.domain.dto.datevxml.DatevApExportDto.ApExportStatisticsDto = error("not used in this test")
        }

    private fun stubBuchExport(): DatevBuchungsstapelExportService =
        object : DatevBuchungsstapelExportService {
            override suspend fun export(
                startDate: String,
                endDate: String,
                exportedByUserId: UUID,
                periodLabel: String,
                source: DatevExportSource,
            ): DatevExportLogDto = dummyDto(startDate, endDate, exportedByUserId)

            override suspend fun export(
                startDate: String,
                endDate: String,
                exportedByUserId: UUID,
                periodLabel: String,
                source: DatevExportSource,
                cashBookIds: List<Long>?,
            ): DatevExportLogDto = dummyDto(startDate, endDate, exportedByUserId)

            override suspend fun exportTransient(
                startDate: String,
                endDate: String,
                exportedByUserId: UUID,
            ): TransientExportResult = error("not used in this test")

            override suspend fun exportTransient(
                startDate: String,
                endDate: String,
                exportedByUserId: UUID,
                cashBookIds: List<Long>?,
            ): TransientExportResult = error("not used in this test")
        }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    fun `preview with no log rows returns single most-recent period`() =
        runBlocking {
            val svc =
                DatevExportCatchupServiceImpl(
                    logRepo = repo,
                    xmlExport = successStub(),
                    apExport = stubApExport(),
                    buchExport = stubBuchExport(),
                )
            val result = svc.preview(DatevExportType.KASSENBUCH, DatevExportCadence.MONTHLY)
            val periods = result.periods
            assertEquals(1, periods.size, "Expected exactly one bootstrap period")
        }

    @Test
    fun `execute with all periods covered returns empty exported and failed`() =
        runBlocking {
            val now =
                kotlin.time.Clock.System
                    .now()
            val tz = TimeZone.currentSystemDefault()
            val today = now.toLocalDateTime(tz).date

            @Suppress("DEPRECATION")
            val currentMonthStart = LocalDate(today.year, today.monthNumber, 1)
            val lastMonthStart = currentMonthStart.plus(DatePeriod(months = -1))

            val periodStart = LocalDateTime(lastMonthStart, LocalTime(0, 0))
            val periodEnd = LocalDateTime(currentMonthStart, LocalTime(0, 0))

            dbQuery {
                repo.insert(
                    exportType = DatevExportType.KASSENBUCH,
                    periodStart = periodStart,
                    periodEnd = periodEnd,
                    exportedByUserId = UUID.randomUUID(),
                    source = DatevExportSource.AUTO,
                    fileHash = "aabbcc",
                    fileName = "kassenbuch.xml",
                    rowCount = 1,
                    entryCount = 1,
                    periodFolder = "test",
                )
            }

            val svc =
                DatevExportCatchupServiceImpl(
                    logRepo = repo,
                    xmlExport = successStub(),
                    apExport = stubApExport(),
                    buchExport = stubBuchExport(),
                )
            val data = svc.execute(DatevExportType.KASSENBUCH, DatevExportCadence.MONTHLY, UUID.randomUUID())
            assertTrue(data.exported.isEmpty(), "No periods to export — exported list should be empty")
            assertTrue(data.failed.isEmpty(), "No periods to export — failed list should be empty")
        }

    /**
     * Checklist 23.5: a catch-up of an ad-hoc-only type answered 200 with the refusal repeated as a
     * per-period failure. It must be refused up front — preview and run alike — with a 400 and a
     * code of its own, and before any exporter is called.
     */
    @Test
    fun `preview and execute refuse a type with no schedule up front`() =
        runBlocking {
            var exporterCalls = 0
            val svc =
                DatevExportCatchupServiceImpl(
                    logRepo = repo,
                    xmlExport =
                        ConfigurableXmlStub { s, e, u, _ ->
                            exporterCalls++
                            dummyDto(s, e, u)
                        },
                    apExport = stubApExport(),
                    buchExport = stubBuchExport(),
                )
            listOf(DatevExportType.BUCHUNGSSTAPEL_MIT_BELEGEN, DatevExportType.AR_LEDGER).forEach { type ->
                val onPreview =
                    assertFailsWith<PosException> { svc.preview(type, DatevExportCadence.MONTHLY) }
                val onExecute =
                    assertFailsWith<PosException> {
                        svc.execute(type, DatevExportCadence.MONTHLY, UUID.randomUUID())
                    }
                listOf(onPreview, onExecute).forEach { refusal ->
                    assertEquals(PosErrorCode.DATEV_EXPORT_TYPE_NOT_SCHEDULABLE, refusal.code, "$type")
                    assertEquals(HttpStatusCode.BadRequest, refusal.httpStatus, "$type")
                }
            }
            assertTrue(
                "ad-hoc only" in
                    assertFailsWith<PosException> {
                        svc.execute(DatevExportType.BUCHUNGSSTAPEL_MIT_BELEGEN, DatevExportCadence.MONTHLY, UUID.randomUUID())
                    }.message.orEmpty(),
            )
            assertEquals(0, exporterCalls, "a refused type must not reach any exporter")
        }

    /**
     * admin16 NOTE-7: the catch-up used to continue past a failed period. The cursor is the log's
     * latest periodEnd, so the later period's success moved it over the gap and the failed period
     * was never exported. It stops at the first failure now and reports the rest as deferred.
     */
    @Test
    fun `execute stops at the first failure and defers the later periods`() =
        runBlocking {
            val cursorLdt = seedCursorMonthsAgo(3)

            var callCount = 0
            val configurableStub =
                ConfigurableXmlStub { s, e, u, _ ->
                    callCount++
                    if (callCount == 2) {
                        throw PosException(
                            PosErrorCode.INTERNAL_ERROR,
                            HttpStatusCode.InternalServerError,
                            "forced failure on call $callCount",
                        )
                    }
                    dummyDto(s, e, u)
                }

            val svc =
                DatevExportCatchupServiceImpl(
                    logRepo = repo,
                    xmlExport = configurableStub,
                    apExport = stubApExport(),
                    buchExport = stubBuchExport(),
                )
            val data = svc.execute(DatevExportType.KASSENBUCH, DatevExportCadence.MONTHLY, UUID.randomUUID())

            assertEquals(2, callCount, "nothing is attempted after the failed period")
            assertEquals(1, data.exported.size, "the period before the failure is exported")
            assertEquals(1, data.failed.size, "one period failed")
            assertEquals(1, data.deferred.size, "the period after the failure waits for the next run")
            assertTrue(cursorLdt < kotlinx.datetime.LocalDateTime.parse(data.failed.single().periodStart))
            assertTrue(data.failed.single().periodEnd <= data.deferred.single().periodStart)
        }

    /**
     * The point of stopping: with an exporter that really writes the log (as the production ones
     * do), a period that failed once is the first one exported on the next run.
     */
    @Test
    fun `a failed period is retried on the next run instead of being skipped`() =
        runBlocking {
            seedCursorMonthsAgo(3)

            var failNext = true
            val attempted = mutableListOf<String>()
            val writingStub =
                ConfigurableXmlStub { s, e, u, _ ->
                    attempted += s
                    if (attempted.size == 2 && failNext) {
                        failNext = false
                        throw IllegalStateException("disk full")
                    }
                    runBlocking { dbQuery { insertLog(s, e) } }
                    dummyDto(s, e, u)
                }
            val svc =
                DatevExportCatchupServiceImpl(
                    logRepo = repo,
                    xmlExport = writingStub,
                    apExport = stubApExport(),
                    buchExport = stubBuchExport(),
                )

            val first = svc.execute(DatevExportType.KASSENBUCH, DatevExportCadence.MONTHLY, UUID.randomUUID())
            assertEquals(1, first.failed.size)
            val failedPeriod = first.failed.single()
            val failedStart = failedPeriod.periodStart.substring(0, 10)

            val second = svc.execute(DatevExportType.KASSENBUCH, DatevExportCadence.MONTHLY, UUID.randomUUID())
            assertTrue(second.failed.isEmpty(), "the retry succeeds: ${second.failed}")
            assertTrue(second.deferred.isEmpty())
            assertEquals(failedStart, attempted[2], "the next run starts with the period that failed")
            assertEquals(2, second.exported.size, "the failed and the deferred period are both exported")
        }

    private suspend fun seedCursorMonthsAgo(months: Int): kotlinx.datetime.LocalDateTime {
        val tz = TimeZone.currentSystemDefault()
        val today =
            kotlin.time.Clock.System
                .now()
                .toLocalDateTime(tz)
                .date

        @Suppress("DEPRECATION")
        val currentMonthStart = LocalDate(today.year, today.monthNumber, 1)
        val cursorMonthStart = currentMonthStart.plus(DatePeriod(months = -months))
        val cursorLdt = LocalDateTime(cursorMonthStart, LocalTime(0, 0))
        val seedPeriodStart = LocalDateTime(cursorMonthStart.plus(DatePeriod(months = -1)), LocalTime(0, 0))
        dbQuery {
            repo.insert(
                exportType = DatevExportType.KASSENBUCH,
                periodStart = seedPeriodStart,
                periodEnd = cursorLdt,
                exportedByUserId = UUID.randomUUID(),
                source = DatevExportSource.AUTO,
                fileHash = "seedhash",
                fileName = "seed.xml",
                rowCount = 0,
                entryCount = 0,
                periodFolder = "seed",
            )
        }
        return cursorLdt
    }

    /** Logs a period the way `DatevXmlExportServiceImpl` does: up to 23:59:59 of [endDate], the last included day. */
    private suspend fun insertLog(
        startDate: String,
        endDate: String,
    ) {
        repo.insert(
            exportType = DatevExportType.KASSENBUCH,
            periodStart = LocalDateTime(LocalDate.parse(startDate), LocalTime(0, 0)),
            periodEnd = LocalDateTime(LocalDate.parse(endDate), LocalTime(23, 59, 59)),
            exportedByUserId = UUID.randomUUID(),
            source = DatevExportSource.AUTO,
            fileHash = "h$startDate",
            fileName = "$startDate.zip",
            rowCount = 1,
            entryCount = 1,
            periodFolder = startDate.substring(0, 7),
        )
    }
}

// -------------------------------------------------------------------------
// Helpers
// -------------------------------------------------------------------------

private fun dummyDto(
    startDate: String,
    endDate: String,
    userId: UUID,
): DatevExportLogDto =
    DatevExportLogDto(
        id = System.nanoTime(),
        exportType = "KASSENBUCH",
        periodStart = "${startDate}T00:00:00",
        periodEnd = "${endDate}T23:59:59",
        exportedAt = "2026-05-01T00:00:00",
        exportedByUserId = userId.toString(),
        fileHash = "deadbeef",
        fileName = "stub.zip",
        rowCount = 1,
        entryCount = 1,
        periodFolder = startDate.substring(0, 7),
        source = "AUTO",
    )

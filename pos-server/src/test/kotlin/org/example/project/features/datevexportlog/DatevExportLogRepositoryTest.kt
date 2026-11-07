package org.example.project.features.datevexportlog

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.enums.setting.DatevExportSource
import org.example.project.features.datevexportlog.data.repository.DatevExportLogRepositoryImpl
import org.example.project.features.datevexportlog.domain.models.DatevExportType
import org.example.project.testutils.TestDatabase
import org.example.project.utils.dbQuery
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals

class DatevExportLogRepositoryTest {
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

    @Test
    fun `insert and findOverlapping returns row when periods overlap`() =
        runBlocking {
            val user = UUID.randomUUID()
            val periodStart = LocalDateTime.parse("2026-06-01T00:00:00")
            val periodEnd = LocalDateTime.parse("2026-07-01T00:00:00")

            dbQuery {
                repo.insert(
                    exportType = DatevExportType.KASSENBUCH,
                    periodStart = periodStart,
                    periodEnd = periodEnd,
                    exportedByUserId = user,
                    source = DatevExportSource.AUTO,
                    fileHash = "deadbeef",
                    fileName = "kassenbuch-2026-06.xml",
                    rowCount = 87,
                    entryCount = 87,
                    periodFolder = "test-folder",
                )
            }

            val overlap =
                dbQuery {
                    repo.findOverlapping(
                        periodStart = LocalDateTime.parse("2026-06-15T00:00:00"),
                        periodEnd = LocalDateTime.parse("2026-08-01T00:00:00"),
                        exportType = DatevExportType.KASSENBUCH,
                    )
                }

            assertEquals(1, overlap.size)
            assertEquals("deadbeef", overlap.single().fileHash)
        }

    @Test
    fun `findOverlapping returns nothing when ranges do not overlap`() =
        runBlocking {
            val user = UUID.randomUUID()

            dbQuery {
                repo.insert(
                    exportType = DatevExportType.KASSENBUCH,
                    periodStart = LocalDateTime.parse("2026-06-01T00:00:00"),
                    periodEnd = LocalDateTime.parse("2026-07-01T00:00:00"),
                    exportedByUserId = user,
                    source = DatevExportSource.AUTO,
                    fileHash = "aabbcc",
                    fileName = "kassenbuch-2026-06.xml",
                    rowCount = 10,
                    entryCount = 10,
                    periodFolder = "test-folder",
                )
            }

            val overlap =
                dbQuery {
                    repo.findOverlapping(
                        periodStart = LocalDateTime.parse("2026-07-01T00:00:00"),
                        periodEnd = LocalDateTime.parse("2026-08-01T00:00:00"),
                    )
                }

            assertEquals(0, overlap.size)
        }

    @Test
    fun `findOverlapping filters by exportType when provided`() =
        runBlocking {
            val user = UUID.randomUUID()

            dbQuery {
                repo.insert(
                    exportType = DatevExportType.KASSENBUCH,
                    periodStart = LocalDateTime.parse("2026-06-01T00:00:00"),
                    periodEnd = LocalDateTime.parse("2026-07-01T00:00:00"),
                    exportedByUserId = user,
                    source = DatevExportSource.AUTO,
                    fileHash = "hash-kassenbuch",
                    fileName = "kassenbuch.xml",
                    rowCount = 1,
                    entryCount = 1,
                    periodFolder = "test-folder",
                )
                repo.insert(
                    exportType = DatevExportType.AP_LEDGER,
                    periodStart = LocalDateTime.parse("2026-06-01T00:00:00"),
                    periodEnd = LocalDateTime.parse("2026-07-01T00:00:00"),
                    exportedByUserId = user,
                    source = DatevExportSource.AUTO,
                    fileHash = "hash-ap",
                    fileName = "ap-ledger.xml",
                    rowCount = 2,
                    entryCount = 2,
                    periodFolder = "test-folder",
                )
            }

            val overlap =
                dbQuery {
                    repo.findOverlapping(
                        periodStart = LocalDateTime.parse("2026-06-15T00:00:00"),
                        periodEnd = LocalDateTime.parse("2026-06-20T00:00:00"),
                        exportType = DatevExportType.AP_LEDGER,
                        sourceFilter = DatevExportSource.AUTO,
                    )
                }

            assertEquals(1, overlap.size)
            assertEquals("hash-ap", overlap.single().fileHash)
        }

    @Test
    fun `findOverlapping returns all types when exportType is null`() =
        runBlocking {
            val user = UUID.randomUUID()

            dbQuery {
                repo.insert(
                    exportType = DatevExportType.KASSENBUCH,
                    periodStart = LocalDateTime.parse("2026-06-01T00:00:00"),
                    periodEnd = LocalDateTime.parse("2026-07-01T00:00:00"),
                    exportedByUserId = user,
                    source = DatevExportSource.AUTO,
                    fileHash = "hash-1",
                    fileName = "kassenbuch.xml",
                    rowCount = 1,
                    entryCount = 1,
                    periodFolder = "test-folder",
                )
                repo.insert(
                    exportType = DatevExportType.BUCHUNGSSTAPEL,
                    periodStart = LocalDateTime.parse("2026-06-01T00:00:00"),
                    periodEnd = LocalDateTime.parse("2026-07-01T00:00:00"),
                    exportedByUserId = user,
                    source = DatevExportSource.AUTO,
                    fileHash = "hash-2",
                    fileName = "buchungsstapel.csv",
                    rowCount = 2,
                    entryCount = 2,
                    periodFolder = "test-folder",
                )
            }

            val overlap =
                dbQuery {
                    repo.findOverlapping(
                        periodStart = LocalDateTime.parse("2026-06-15T00:00:00"),
                        periodEnd = LocalDateTime.parse("2026-06-20T00:00:00"),
                        exportType = null,
                        sourceFilter = DatevExportSource.AUTO,
                    )
                }

            assertEquals(2, overlap.size)
        }
}

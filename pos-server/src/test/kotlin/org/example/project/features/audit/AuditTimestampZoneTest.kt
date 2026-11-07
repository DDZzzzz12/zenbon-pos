package org.example.project.features.audit

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import org.example.project.features.audit.data.repository.TransactionAuditRepositoryImpl
import org.example.project.features.audit.data.service.TransactionAuditServiceImpl
import org.example.project.features.audit.domain.models.TransactionAuditLog
import org.example.project.testutils.TestDatabase
import org.example.project.utils.formatter.TimeProvider
import org.example.project.utils.formatter.registerTimeProvider
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import java.util.TimeZone as JavaTimeZone

/**
 * Every audit row must be stamped in the shop's timezone, the way `ExceptionConverter` writes the
 * rows it audits (`currentTimeStamp()`), so the table can be ordered and date-filtered as one set.
 *
 * `TransactionAuditServiceImpl` stamped `timestamp`, and `TransactionAuditRepositoryImpl`
 * `created_at`, with `Clock.System.now().toString()` — a UTC instant with a trailing `Z`. Both
 * columns are `varchar`, and the six live `logTransaction` call sites (TSE queue signing, the TSE
 * admin routes, `TseVorgangSigner`, business partners) all inherited it, so those rows sorted and
 * filtered against everything else as if UTC wall time were local. E2E-2026-09-22 finding R3-5.
 *
 * Three zones are in play on purpose: the JVM default is Tokyo, the shop is Berlin, and the instant
 * is UTC. Only a row written through `currentTimeStamp()` reads Berlin, so the test cannot pass by
 * accident on a machine that happens to run in the shop's zone.
 */
class AuditTimestampZoneTest {
    private val berlin = TimeZone.of("Europe/Berlin")

    /** A fixed shop wall time: not the UTC instant, and not the Tokyo one either. */
    private val shopWallTime = LocalDateTime(2026, 9, 23, 10, 11, 12, 123_456_000)

    private var savedJvmDefault: JavaTimeZone? = null

    @BeforeEach
    fun setup() {
        savedJvmDefault = JavaTimeZone.getDefault()
        JavaTimeZone.setDefault(JavaTimeZone.getTimeZone("Asia/Tokyo"))
        TestDatabase.setup()
        registerTimeProvider(
            object : TimeProvider {
                override fun now(): LocalDateTime = shopWallTime

                override fun getTimeZone(): TimeZone = berlin
            },
        )
    }

    @AfterEach
    fun teardown() {
        registerTimeProvider(TimeProvider.SYSTEM_DEFAULT)
        TestDatabase.teardown()
        savedJvmDefault?.let { JavaTimeZone.setDefault(it) }
    }

    private fun writeOneRow() =
        runBlocking {
            TransactionAuditServiceImpl(TransactionAuditRepositoryImpl(), Json).logTransaction(
                employeeId = "SYSTEM",
                employeeNumber = "SYSTEM",
                authorizationType = "AUTOMATIC",
                authorizingManagerId = "SYSTEM",
                operation = "TSE_QUEUE_SIGNED",
                entityType = "KASSENBUCH",
                entityId = "1",
            )
        }

    private fun storedRow(): TransactionAuditLog = transaction { TransactionAuditLog.all().first() }

    @Test
    fun `the timestamp is the shop's wall time, not the UTC instant`() {
        writeOneRow()

        val stored = storedRow().timestamp
        assertFalse(stored.endsWith("Z"), "a UTC instant leaks the wrong wall time into a local-time column; got $stored")
        assertEquals(shopWallTime.toString(), stored, "the row must carry the same form ExceptionConverter writes")
    }

    @Test
    fun `created_at is stamped the same way as the timestamp`() {
        writeOneRow()

        val stored = storedRow()
        assertFalse(stored.createdAt.endsWith("Z"), "created_at is written in the repository and drifted the same way")
        assertEquals(
            stored.timestamp,
            stored.createdAt,
            "one column uniform and the other split is harder to spot than both being wrong together",
        )
    }
}

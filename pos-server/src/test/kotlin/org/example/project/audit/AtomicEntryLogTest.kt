package org.example.project.audit

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.enums.setup.LogOperation
import org.example.project.core.domain.enums.websocket.ChangeEntityType
import org.example.project.features.audit.database.tables.EntryLogTable
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.example.project.utils.dbQuery
import org.example.project.utils.errorHandling.ExceptionConverter
import org.example.project.utils.log.EntryLogConfig
import org.example.project.utils.log.EntryLogData
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * Verifies that [ExceptionConverter.recordCrudLog] writes the entry_log row in
 * the CALLER's transaction, so a CRUD log is atomic with the mutation: it
 * survives only if the mutation commits, and is rolled back if it does not.
 */
class AtomicEntryLogTest {
    @BeforeEach
    fun setup() {
        TestDatabase.setup()
    }

    @AfterEach
    fun tearDown() = TestDatabase.teardown()

    private suspend fun countEntryLogRows(): Long = dbQuery { EntryLogTable.selectAll().count() }

    private suspend fun countRows(
        type: ChangeEntityType,
        operation: LogOperation,
    ): Long =
        dbQuery {
            EntryLogTable
                .selectAll()
                .where { (EntryLogTable.type eq type) and (EntryLogTable.operation eq operation) }
                .count()
        }

    @Test
    fun `crud log is rolled back when the mutation transaction throws`() =
        runBlocking {
            val category = TestData.createCategory()
            val before = countEntryLogRows()

            val thrown =
                runCatching {
                    dbQuery {
                        val product =
                            org.example.project.features.product.domain.models.Product.new {
                                this.productNumber = "ATOMIC_FAIL"
                                this.name = "Atomic Fail"
                                this.price = java.math.BigDecimal("1.0000")
                                this.category = category
                                this.productType = org.example.project.core.domain.enums.product.ProductType.FOOD
                                this.isActive = true
                                this.updatedAt =
                                    org.example.project.utils.formatter
                                        .currentTimeStamp()
                            }
                        ExceptionConverter.recordCrudLog(
                            type = ChangeEntityType.PRODUCT,
                            operation = LogOperation.CREATE,
                            entityId = product.id.value.toString(),
                            entityName = "Atomic Fail",
                        )
                        throw IllegalStateException("boom — force rollback")
                    }
                }

            assertTrue(thrown.isFailure, "block should have thrown")
            assertEquals(before, countEntryLogRows(), "entry_log row must be rolled back with the mutation")
        }

    @Test
    fun `crud log is committed exactly once when the mutation transaction succeeds`() =
        runBlocking {
            val category = TestData.createCategory()
            val before = countEntryLogRows()

            dbQuery {
                val product =
                    org.example.project.features.product.domain.models.Product.new {
                        this.productNumber = "ATOMIC_OK"
                        this.name = "Atomic Ok"
                        this.price = java.math.BigDecimal("1.0000")
                        this.category = category
                        this.productType = org.example.project.core.domain.enums.product.ProductType.FOOD
                        this.isActive = true
                        this.updatedAt =
                            org.example.project.utils.formatter
                                .currentTimeStamp()
                    }
                ExceptionConverter.recordCrudLog(
                    type = ChangeEntityType.PRODUCT,
                    operation = LogOperation.CREATE,
                    entityId = product.id.value.toString(),
                    entityName = "Atomic Ok",
                )
            }

            assertEquals(before + 1, countEntryLogRows(), "exactly one entry_log row must be committed")
        }

    @Test
    fun `catchingThrows rolls back the entry_log when the inner dbQuery block throws`() =
        runBlocking {
            val category = TestData.createCategory()
            val before = countEntryLogRows()

            // Mirrors the production path: catchingThrows(entryLogConfig=...) { dbQuery { mutation } }.
            // The outer ambient transaction (added for atomicity) and the inner service dbQuery nest;
            // a throw inside the block must roll back the mutation AND the SUCCESS-path change-feed
            // (PRODUCT/CREATE) row. The catch path then writes one best-effort ERROR row in its OWN
            // separate transaction (by design — error logging is non-blocking), so total count rises
            // by exactly that one ERROR row, never by a committed CREATE row.
            val thrown =
                runCatching {
                    ExceptionConverter.catchingThrows(
                        context = "test(catchingThrows rollback)",
                        entryLogConfig =
                            EntryLogConfig<Unit>(
                                logType = ChangeEntityType.PRODUCT,
                                logOperation = LogOperation.CREATE,
                                extractLogData = { EntryLogData("nope", "Should Not Commit", "should be rolled back") },
                            ),
                    ) {
                        dbQuery {
                            org.example.project.features.product.domain.models.Product.new {
                                this.productNumber = "CT_FAIL"
                                this.name = "Catching Fail"
                                this.price = java.math.BigDecimal("1.0000")
                                this.category = category
                                this.productType = org.example.project.core.domain.enums.product.ProductType.FOOD
                                this.isActive = true
                                this.updatedAt =
                                    org.example.project.utils.formatter
                                        .currentTimeStamp()
                            }
                            throw IllegalStateException("boom — force rollback")
                        }
                    }
                }

            assertTrue(thrown.isFailure, "catchingThrows should have propagated the exception")
            // The success-path CREATE change-feed row must NOT survive (rolled back with the mutation).
            assertEquals(
                0L,
                countRows(ChangeEntityType.PRODUCT, LogOperation.CREATE),
                "the PRODUCT/CREATE change-feed row must be rolled back when the block throws",
            )
            // Exactly one row was added overall — the intentional best-effort ERROR row from the catch path.
            assertEquals(before + 1, countEntryLogRows(), "only the best-effort ERROR row should be added")
            assertEquals(
                1L,
                countRows(ChangeEntityType.PRODUCT, LogOperation.ERROR),
                "the catch path writes one ERROR row in its own transaction",
            )
        }

    @Test
    fun `catchingThrows commits exactly one entry_log row when the block succeeds`() =
        runBlocking {
            val category = TestData.createCategory()
            val before = countEntryLogRows()

            ExceptionConverter.catchingThrows(
                context = "test(catchingThrows success)",
                entryLogConfig =
                    EntryLogConfig<Long>(
                        logType = ChangeEntityType.PRODUCT,
                        logOperation = LogOperation.CREATE,
                        extractLogData = { productId -> EntryLogData(productId.toString(), "Catching Ok", "created") },
                    ),
            ) {
                dbQuery {
                    val product =
                        org.example.project.features.product.domain.models.Product.new {
                            this.productNumber = "CT_OK"
                            this.name = "Catching Ok"
                            this.price = java.math.BigDecimal("1.0000")
                            this.category = category
                            this.productType = org.example.project.core.domain.enums.product.ProductType.FOOD
                            this.isActive = true
                            this.updatedAt =
                                org.example.project.utils.formatter
                                    .currentTimeStamp()
                        }
                    product.id.value
                }
            }

            assertEquals(before + 1, countEntryLogRows(), "exactly one entry_log row must be committed via catchingThrows")
        }
}

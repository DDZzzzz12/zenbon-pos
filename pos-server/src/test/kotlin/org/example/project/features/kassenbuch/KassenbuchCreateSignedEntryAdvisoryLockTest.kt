package org.example.project.features.kassenbuch

import com.zaxxer.hikari.HikariConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.example.project.core.domain.dto.document.DocumentDto
import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.database.DatabaseFactory
import org.example.project.features.document.domain.models.Document
import org.example.project.features.document.domain.service.DocumentManagementService
import org.example.project.features.document.domain.service.PdfGenerationService
import org.example.project.features.employee.data.repository.EmployeeRepositoryImpl
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.kassenbuch.data.repository.KassenbuchRepositoryImpl
import org.example.project.features.kassenbuch.data.service.KassenbuchServiceImpl
import org.example.project.features.kassenbuch.database.tables.KassenbuchHeaderTable
import org.example.project.features.kassenbuch.domain.models.KassenbuchHeader
import org.example.project.features.setup.data.repository.ShopInfoRepositoryImpl
import org.example.project.features.terminal.data.repository.TerminalRepositoryImpl
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.features.tse.data.service.TseVorgangSigner
import org.example.project.features.zBon.data.repository.ZBonRepositoryImpl
import org.example.project.infrastructure.plugins.initDB
import org.example.project.testutils.PostgresTestDatabase
import org.example.project.testutils.TestData
import org.example.project.testutils.testKassenbuchDocuments
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.koin.core.context.stopKoin
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Round-7 D9, part 2 — `createSignedEntry` must serialize against `createReport`.
 *
 * The heal-forward sweep (committed separately) removes the *permanent* brick a stranded entry
 * causes, but does not stop the entry being stranded in the first place: an entry committed inside
 * a closing's `REPEATABLE_READ` snapshot is invisible to that closing and lands in the next one
 * (wrong-period attribution) with a transient "open business" window. `createReport` already takes
 * `pg_advisory_xact_lock(1)` for its whole transaction; `createSignedEntry` must take the SAME lock
 * so a Kassenbuch booking either commits fully before the closing pins its snapshot or is
 * serialized after it — never in between.
 *
 * This is a **Postgres** test on purpose: the advisory lock is a no-op on H2 (the suite aliases
 * `PG_ADVISORY_XACT_LOCK` to `return key;`), so the serialization it provides can only be observed
 * against a real PostgreSQL. A separate transaction holds lock 1; the assertion is that
 * `createSignedEntry` cannot make progress while that lock is held, and resumes once it is released.
 *
 * The mutation this guard catches (rule R13): delete the `pg_advisory_xact_lock(1)` statement from
 * `createSignedEntry` and this test goes RED — the call completes while the lock is held, because
 * it no longer contends for it.
 */
@Tag("postgres")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("KassenbuchServiceImpl.createSignedEntry serializes on the Z-Bon advisory lock (R7-D9)")
class KassenbuchCreateSignedEntryAdvisoryLockTest {
    private val DEV = "test-device"
    private val ADVISORY_LOCK_KEY = 1

    private lateinit var pg: PostgresTestDatabase
    private lateinit var service: KassenbuchServiceImpl
    private lateinit var employee: Employee
    private lateinit var emp: String

    @BeforeAll
    fun startDatabase() {
        pg = PostgresTestDatabase.start()
        stopKoin()
        if (DatabaseFactory.isInitialized()) DatabaseFactory.close()

        val config =
            HikariConfig().apply {
                jdbcUrl = pg.jdbcUrl
                driverClassName = "org.postgresql.Driver"
                username = "postgres"
                password = "postgres"
                maximumPoolSize = 6
                isAutoCommit = false
            }
        // Fresh PG → initDB creates the full current schema via Exposed (dialect-agnostic).
        initDB(config, runMigrations = false)

        val tseFactory =
            org.example.project.features.tse.domain.factory.TseServiceFactory(
                tseTransactionRepository = noopProxy(),
                tseSignClient = noopProxy(),
                signer = TseVorgangSigner(noopProxy(), noopProxy(), noopProxy()),
            )
        service =
            KassenbuchServiceImpl(
                kassenbuchRepository = KassenbuchRepositoryImpl(testKassenbuchDocuments()),
                zBonRepository = ZBonRepositoryImpl(),
                employeeRepository = EmployeeRepositoryImpl(),
                shopInfoRepository = ShopInfoRepositoryImpl(),
                tseServiceFactory = tseFactory,
                tseSigningQueueService = noopProxy(),
                terminalRepository = TerminalRepositoryImpl(),
                documents = testKassenbuchDocuments(pdfGenerationService = fakePdfGenerationService, documentManagementService = fakeDocumentManagementService),
                configManager = disabledTseConfigManager,
            )

        TestData.createShopInfo()
        employee = TestData.createAdmin()
        emp = employee.id.value.toString()
        transaction {
            Terminal.new {
                this.deviceId = DEV
                this.clientId = "client-1"
                this.terminalBrand = "TestBrand"
                this.terminalModel = "TestModel"
                this.terminalSerialNr = "SN-1"
                this.terminalSwBrand = "SwBrand"
                this.terminalSwVersion = "1.0"
                this.isActive = true
                this.tseClientRegistered = true
                this.registeredAt = currentTimeStamp()
                this.lastSeenAt = currentTimeStamp()
            }
        }
    }

    @AfterAll
    fun stopDatabase() {
        DatabaseFactory.close()
        pg.close()
    }

    @Test
    fun `createSignedEntry cannot proceed while a concurrent transaction holds advisory lock 1`() =
        runBlocking {
            // F59: the Kassenminus guard refuses an outflow from an unfunded till, so the probe's
            // 50.00 expense needs 50.00 in the Ladenkasse first. A deposit, booked before the lock is
            // taken; it does not touch what this test asserts (the probe's own belegtext).
            service.createEntry(emp, DEV, entry(KassenbuchEntryType.CASH_DEPOSIT, "50.00", cashBookId = null, belegtext = "Wechselgeld"))
            val docId = newDocumentId("wareneinkauf.pdf")
            val request =
                KassenbuchDto.CreateKassenbuchEntryRequestDto(
                    entryType = KassenbuchEntryType.CASH_EXPENSE,
                    lines =
                        listOf(
                            KassenbuchDto.EntryLineDto(vatRate = "19", amount = "50.0", belegtext = "Waren"),
                        ),
                    belegtext = UNIQUE_BELEGTEXT,
                    documentId = docId,
                )

            // A concurrent transaction holds advisory lock 1 — exactly what createReport holds for
            // the whole of its closing transaction.
            val sideConn = pg.dataSource.connection
            sideConn.autoCommit = false
            sideConn
                .prepareStatement("SELECT pg_advisory_xact_lock($ADVISORY_LOCK_KEY)")
                .executeQuery()
                .use { it.next() }

            try {
                val call = async(Dispatchers.IO) { service.createEntry(emp, DEV, request) }

                // While the lock is held, createSignedEntry must block on its first statement.
                delay(2_000)
                assertFalse(
                    call.isCompleted,
                    "createSignedEntry completed while advisory lock 1 was held by a concurrent " +
                        "transaction — it is NOT serializing against createReport (R7-D9). Add " +
                        "`pg_advisory_xact_lock(1)` as its first statement.",
                )

                // Releasing the lock must let it finish — proving it was blocked ONLY on the lock.
                sideConn.commit()
                withTimeout(10_000) { call.await() }
                assertTrue(call.isCompleted, "createSignedEntry did not resume after the lock was released")
            } finally {
                if (!sideConn.isClosed) sideConn.close()
            }

            // The entry was created exactly once (not lost, not duplicated by the wait).
            transaction {
                assertEquals(
                    1L,
                    KassenbuchHeader.find { KassenbuchHeaderTable.belegtext eq UNIQUE_BELEGTEXT }.count(),
                    "the entry must be created exactly once after the lock is released",
                )
            }
        }

    /**
     * F59 — the Kassenminus guard reads the balance UNDER advisory lock 1, so two withdrawals racing
     * for the same cash serialize and the second sees the first. Both calls are started while a side
     * transaction holds the lock, so both are queued at their first statement and contend for real;
     * releasing it lets them run one after the other. Without the lock (or with the guard read before
     * it) both would read 10.00 and both would pass, leaving the Tresor at −2.00.
     */
    @Test
    fun `two withdrawals racing for the same cash cannot both pass the Kassenminus guard`() =
        runBlocking {
            val tresor =
                transaction {
                    org.example.project.features.kassenbuch.data.repository
                        .CashBookRepositoryImpl()
                        .create(
                            org.example.project.features.setup.domain.models.ShopInfo
                                .all()
                                .first(),
                            "Race-Tresor",
                            false,
                            "1010",
                        ).id.value
                }
            service.createEntry(emp, DEV, entry(KassenbuchEntryType.CASH_DEPOSIT, "10.00", tresor, "Einlage"))

            val sideConn = pg.dataSource.connection
            sideConn.autoCommit = false
            sideConn
                .prepareStatement("SELECT pg_advisory_xact_lock($ADVISORY_LOCK_KEY)")
                .executeQuery()
                .use { it.next() }
            val outcomes =
                try {
                    val first =
                        async(
                            Dispatchers.IO,
                        ) { runCatching { service.createEntry(emp, DEV, entry(KassenbuchEntryType.CASH_EXPENSE, "6.00", tresor, "Race A")) } }
                    val second =
                        async(
                            Dispatchers.IO,
                        ) { runCatching { service.createEntry(emp, DEV, entry(KassenbuchEntryType.CASH_EXPENSE, "6.00", tresor, "Race B")) } }
                    delay(1_000)
                    assertFalse(first.isCompleted || second.isCompleted, "both must be queued on the lock")
                    sideConn.commit()
                    withTimeout(15_000) { listOf(first.await(), second.await()) }
                } finally {
                    if (!sideConn.isClosed) sideConn.close()
                }

            assertEquals(1, outcomes.count { it.isSuccess }, "exactly one withdrawal fits: $outcomes")
            val refusal = outcomes.single { it.isFailure }.exceptionOrNull()
            assertTrue(
                refusal is org.example.project.infrastructure.errors.PosException &&
                    refusal.code == org.example.project.core.error.PosErrorCode.KASSENBUCH_INSUFFICIENT_CASH,
                "the other is the Kassenminus refusal, not some other error: $refusal",
            )
            transaction {
                val balance =
                    KassenbuchRepositoryImpl(testKassenbuchDocuments()).getCurrentBalance(
                        org.example.project.features.setup.domain.models.ShopInfo
                            .all()
                            .first(),
                        tresor,
                    )
                assertEquals(0, java.math.BigDecimal("4.00").compareTo(balance), "10.00 − 6.00, never −2.00")
            }
        }

    private fun entry(
        type: KassenbuchEntryType,
        amount: String,
        cashBookId: Long?,
        belegtext: String,
    ) = KassenbuchDto.CreateKassenbuchEntryRequestDto(
        entryType = type,
        lines = listOf(KassenbuchDto.EntryLineDto(vatRate = "19", amount = amount)),
        belegtext = belegtext,
        documentId = newDocumentId("$belegtext.pdf"),
        cashBookId = cashBookId,
    )

    // ---------------------------------------------------------------- fixtures / fakes

    private companion object {
        const val UNIQUE_BELEGTEXT = "R7D9-ADVISORY-LOCK-PROBE"
    }

    private var docCounter = 0

    private fun newDocumentId(filename: String): Long =
        transaction {
            Document
                .new {
                    this.filename = filename
                    this.fileType = "INVOICE"
                    this.filePath = "/tmp/test/${++docCounter}-$filename"
                    this.fileSize = 1024L
                    this.uploadedAt = currentTimeStamp()
                    this.uploadedBy = employee
                    this.isActive = true
                    this.contentHash = "hash-$docCounter-$filename"
                    this.mediaType = "application/pdf"
                }.id.value
        }

    /** Throwing proxy for collaborators that must NOT be reached in DISABLED-TSE mode. */
    private inline fun <reified T : Any> noopProxy(): T {
        @Suppress("UNCHECKED_CAST")
        return Proxy.newProxyInstance(
            T::class.java.classLoader,
            arrayOf(T::class.java),
        ) { _, method, _ ->
            throw NotImplementedError("Stub: ${T::class.simpleName}.${method.name} should not be called")
        } as T
    }

    private val disabledTseConfigManager: org.example.project.core.config.IShopConfigurationManager by lazy {
        Proxy.newProxyInstance(
            org.example.project.core.config.IShopConfigurationManager::class.java.classLoader,
            arrayOf(org.example.project.core.config.IShopConfigurationManager::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getTseMode" -> org.example.project.core.domain.enums.setup.TseMode.DISABLED
                "getTseConfig" ->
                    org.example.project.core.domain.dto.tse.TseConfigDto.TseConfig(
                        timeAdminPin = "00000",
                    )
                // createSignedEntry checks the Beleg-age limit (e1240a3c2); the unset default is what
                // a shop without the setting gets. A proxied suspend fun returns its value directly.
                "getKassenbuchMaxBelegAgeDays" ->
                    org.example.project.core.utils.KassenbuchBelegDateRule
                        .effectiveMaxAgeDays(null)
                else -> throw NotImplementedError(
                    "Stub: IShopConfigurationManager.${method.name} should not be called",
                )
            }
        } as org.example.project.core.config.IShopConfigurationManager
    }

    private val fakePdfGenerationService: PdfGenerationService by lazy {
        Proxy.newProxyInstance(
            PdfGenerationService::class.java.classLoader,
            arrayOf(PdfGenerationService::class.java),
        ) { _, method, _ ->
            if (method.name == "generateEigenbelegPdf") {
                org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
                    .currentOrNull()
                    ?: error("generateEigenbelegPdf must be called within a transaction")
                val id = newDocumentId("storno-eigenbeleg.pdf")
                DocumentDto.DocumentResponseDto(
                    id = id,
                    filename = "storno-eigenbeleg.pdf",
                    fileType = "INVOICE",
                    fileSize = 1024L,
                    uploadedAt = "2026-06-24T00:00:00",
                    uploadedBy = emp,
                    downloadUrl = "/download/$id",
                    formattedFileSize = "1.0 KB",
                    monthFolder = "2026-06",
                )
            } else {
                throw NotImplementedError("PdfGenerationService.${method.name} should not be called")
            }
        } as PdfGenerationService
    }

    private val fakeDocumentManagementService: DocumentManagementService by lazy {
        Proxy.newProxyInstance(
            DocumentManagementService::class.java.classLoader,
            arrayOf(DocumentManagementService::class.java),
        ) { _, method, _ ->
            if (method.name == "copyDocument") {
                newDocumentId("copied-doc.pdf")
            } else {
                throw NotImplementedError("DocumentManagementService.${method.name} should not be called")
            }
        } as DocumentManagementService
    }
}

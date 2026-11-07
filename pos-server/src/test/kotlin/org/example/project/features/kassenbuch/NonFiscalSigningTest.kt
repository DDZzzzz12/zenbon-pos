package org.example.project.features.kassenbuch

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.document.DocumentDto
import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.core.domain.enums.tse.TseEntityType
import org.example.project.core.domain.enums.tse.TseLateSigningReason
import org.example.project.core.domain.enums.tse.TseSigningStatus
import org.example.project.core.error.PosErrorCode
import org.example.project.features.audit.domain.service.TransactionAuditService
import org.example.project.features.document.domain.models.Document
import org.example.project.features.document.domain.service.DocumentManagementService
import org.example.project.features.document.domain.service.PdfGenerationService
import org.example.project.features.employee.data.repository.EmployeeRepositoryImpl
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.kassenbuch.data.repository.CashBookRepositoryImpl
import org.example.project.features.kassenbuch.data.repository.KassenbuchRepositoryImpl
import org.example.project.features.kassenbuch.data.service.KassenbuchServiceImpl
import org.example.project.features.kassenbuch.database.tables.KassenbuchHeaderTable
import org.example.project.features.kassenbuch.domain.models.KassenbuchHeader
import org.example.project.features.setup.data.repository.ShopInfoRepositoryImpl
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.terminal.data.repository.TerminalRepositoryImpl
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.features.tse.data.service.TseSigningQueueServiceImpl
import org.example.project.features.tse.data.service.TseVorgangSigner
import org.example.project.features.tse.domain.provider.TseSignProvider
import org.example.project.features.tse.domain.repository.TseSigningQueueRepository
import org.example.project.features.tse.domain.repository.TseTransactionRepository
import org.example.project.features.zBon.data.repository.ZBonRepositoryImpl
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.example.project.testutils.testKassenbuchDocuments
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

@DisplayName("Non-fiscal cash books are never TSE-signed")
class NonFiscalSigningTest {
    // === fixture copied verbatim from KassenbuchSignedEntryServiceTest (lines 41-211) ===
    private val DEV = "test-device"

    private lateinit var service: KassenbuchServiceImpl
    private lateinit var repository: KassenbuchRepositoryImpl
    private lateinit var shop: ShopInfo
    private lateinit var employee: Employee
    private lateinit var emp: String

    @BeforeEach
    fun setup() {
        TestDatabase.setup()
        repository = KassenbuchRepositoryImpl(testKassenbuchDocuments())

        // Real TseServiceFactory: with no global TSE config it falls back to DISABLED mode,
        // whose signTransaction returns null (no signing, no FAIL/QUEUE branch). The repo/client
        // collaborators are never touched in DISABLED mode, so noop proxies are safe.
        val tseFactory =
            org.example.project.features.tse.domain.factory.TseServiceFactory(
                tseTransactionRepository = noopProxy(),
                tseSignClient = noopProxy(),
                signer = TseVorgangSigner(noopProxy(), noopProxy(), noopProxy()),
            )

        service =
            KassenbuchServiceImpl(
                kassenbuchRepository = repository,
                zBonRepository = ZBonRepositoryImpl(),
                employeeRepository = EmployeeRepositoryImpl(),
                shopInfoRepository = ShopInfoRepositoryImpl(),
                tseServiceFactory = tseFactory,
                tseSigningQueueService = noopProxy(),
                terminalRepository = TerminalRepositoryImpl(),
                documents = testKassenbuchDocuments(pdfGenerationService = fakePdfGenerationService, documentManagementService = fakeDocumentManagementService),
                configManager = disabledTseConfigManager,
            )

        shop = TestData.createShopInfo()
        employee = TestData.createAdmin()
        emp = employee.id.value.toString()

        // Terminal so terminalRepository.findByDeviceId(DEV) resolves with a clientId.
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

    @AfterEach
    fun teardown() {
        TestDatabase.teardown()
    }

    // -------------------------------------------------------------------------
    // Fakes
    // -------------------------------------------------------------------------

    /** Throwing proxy for collaborators that must NOT be called in these tests. */
    private inline fun <reified T : Any> noopProxy(): T {
        @Suppress("UNCHECKED_CAST")
        return Proxy.newProxyInstance(
            T::class.java.classLoader,
            arrayOf(T::class.java),
        ) { _, method, _ ->
            throw NotImplementedError("Stub: ${T::class.simpleName}.${method.name} should not be called in correction tests")
        } as T
    }

    /**
     * Config manager stub: TSE is DISABLED (mode=DISABLED, failureBehavior=QUEUE), which is the
     * scenario these correction tests run under (the real TseServiceFactory's signTransaction
     * returns null → resolver yields DISABLED). Only the two TSE accessors are answered; any other
     * method is a programming error in this harness.
     */
    private val disabledTseConfigManager: org.example.project.core.config.IShopConfigurationManager by lazy {
        @Suppress("UNCHECKED_CAST")
        Proxy.newProxyInstance(
            org.example.project.core.config.IShopConfigurationManager::class.java.classLoader,
            arrayOf(org.example.project.core.config.IShopConfigurationManager::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getTseMode" -> tseMode
                // createEntry reads the shop's Beleg-age limit for its date guard.
                "getKassenbuchMaxBelegAgeDays" -> org.example.project.core.utils.KassenbuchBelegDateRule.DEFAULT_MAX_AGE_DAYS
                "getTseConfig" ->
                    org.example.project.core.domain.dto.tse.TseConfigDto.TseConfig(
                        timeAdminPin = "00000",
                    )
                // The money DTOs now carry the shop currency, so mappers resolve it on these paths.
                "getDefaultCurrency" -> "EUR"
                else -> throw NotImplementedError(
                    "Stub: IShopConfigurationManager.${method.name} should not be called in correction tests",
                )
            }
        } as org.example.project.core.config.IShopConfigurationManager
    }

    /** The mode the stubbed config manager reports; the default (DISABLED) is what most tests here run under. */
    private var tseMode: org.example.project.core.domain.enums.setup.TseMode =
        org.example.project.core.domain.enums.setup.TseMode.DISABLED

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

    /** PdfGenerationService fake: generateEigenbelegPdf creates a real Document, returns its summary. */
    private val fakePdfGenerationService: PdfGenerationService by lazy {
        @Suppress("UNCHECKED_CAST")
        Proxy.newProxyInstance(
            PdfGenerationService::class.java.classLoader,
            arrayOf(PdfGenerationService::class.java),
        ) { _, method, _ ->
            if (method.name == "generateEigenbelegPdf") {
                // Mirror the real impl: it persists a Document + maps the entity, so it MUST
                // run within a transaction. This guards against the caller forgetting dbQuery.
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

    /** DocumentManagementService fake: copyDocument creates a fresh Document row, returns its id. */
    private val fakeDocumentManagementService: DocumentManagementService by lazy {
        @Suppress("UNCHECKED_CAST")
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

    // === end of copied fixture ===

    private val books = CashBookRepositoryImpl()

    private fun tresorId(): Long = transaction { books.create(shop, "Tresor", false, "1010").id.value }

    private fun lastHeader(): KassenbuchHeader =
        transaction {
            KassenbuchHeader.all().orderBy(KassenbuchHeaderTable.id to SortOrder.DESC).first()
        }

    private suspend fun expenseIn(
        cashBookId: Long?,
        deviceId: String = DEV,
    ) {
        // F59: the 50.00 expense is paid out of what the book holds (Kassenminus guard). Funded
        // through the repository — unsigned, no terminal — before the expense, which therefore stays
        // the newest header `lastHeader()` reads.
        TestData.fundCashBook("50.00", cashBookId)
        val docId = newDocumentId("beleg.pdf")
        service.createEntry(
            employee = emp,
            deviceId = deviceId,
            request =
                KassenbuchDto.CreateKassenbuchEntryRequestDto(
                    entryType = KassenbuchEntryType.CASH_EXPENSE,
                    lines = listOf(KassenbuchDto.EntryLineDto(vatRate = "19", amount = "50.00")),
                    belegtext = "Test-Ausgabe",
                    documentId = docId,
                    cashBookId = cashBookId,
                ),
        )
    }

    @Test
    fun `an entry in a non-fiscal book is never signed`() =
        runBlocking {
            expenseIn(tresorId())

            transaction {
                val header = lastHeader()
                assertEquals(
                    TseSigningStatus.NOT_APPLICABLE,
                    header.tseSigningStatus,
                    "a Tresor is not an elektronisches Aufzeichnungssystem — §146a does not reach it",
                )
                assertNull(header.tseTransaction, "no TSE transaction may be attached")
                assertEquals(false, header.cashBook.isFiscal)
            }
        }

    @Test
    fun `an entry in the fiscal book keeps its pre-cash-book signing outcome`() =
        runBlocking {
            expenseIn(null) // null = the shop's fiscal Ladenkasse

            transaction {
                val header = lastHeader()
                // This harness runs TseMode.DISABLED and signTransaction returns null, so
                // TseSignOutcome.NoSignature -> TseSigningStatus.DISABLED
                // (TseSigningOutcomeResolver.kt:26-27). Asserting SIGNED here would be wrong.
                assertEquals(TseSigningStatus.DISABLED, header.tseSigningStatus)
                assertNotEquals(TseSigningStatus.NOT_APPLICABLE, header.tseSigningStatus)
                assertEquals(true, header.cashBook.isFiscal)
            }
        }

    @Test
    fun `the enqueue chokepoint refuses a non-fiscal entry`() =
        runBlocking {
            // Real DB-backed setup: create a genuine Kassenbuch header inside a Tresor via the
            // same createEntry path production uses (Phase 1 only matters here — Phase 2 is
            // already proven skipped by the first test), then attempt a direct enqueue of that
            // header — proving the guard holds even if a future caller writes its own query
            // rather than going through createEntry/createSignedEntry.
            expenseIn(tresorId())
            val headerId = transaction { lastHeader().id.value }

            // Construct the REAL TseSigningQueueServiceImpl (not the noop proxy used by
            // `service`), with noop proxies for its own collaborators — the guard must fire
            // before any of them are touched.
            val queueService =
                TseSigningQueueServiceImpl(
                    queueRepository = noopProxy<TseSigningQueueRepository>(),
                    tseTransactionRepository = noopProxy<TseTransactionRepository>(),
                    tseSignClient = noopProxy<TseSignProvider>(),
                    transactionAuditService = noopProxy<TransactionAuditService>(),
                )

            assertFailsWith<IllegalArgumentException> {
                queueService.enqueue(
                    referenceId = headerId.toString(),
                    entityType = TseEntityType.KASSENBUCH,
                    processType = "AVSonstige",
                    processData = "irrelevant",
                    clientId = "client-1",
                    reason = TseLateSigningReason.TSE_UNAVAILABLE,
                    reasonText = "test",
                )
            }
            Unit
        }

    @Test
    fun `a non-fiscal entry can be created without a signing terminal`() =
        runBlocking {
            // adminApp's exact situation: its synthetic `admin-web-*` device id can never be a
            // registered terminal (requireSigningTerminal refuses it with 403 by design), so
            // resolving one unconditionally locked the console out of Tresor bookings entirely —
            // for a clientId a NOT_APPLICABLE entry never uses.
            expenseIn(tresorId(), deviceId = "admin-web-abc123")

            transaction {
                val header = lastHeader()
                assertEquals(TseSigningStatus.NOT_APPLICABLE, header.tseSigningStatus)
                assertNull(header.tseTransaction, "no TSE transaction may be attached")
                assertEquals(false, header.cashBook.isFiscal)
            }
        }

    @Test
    fun `a FISCAL entry still requires a signing terminal when the TSE is on`() =
        runBlocking {
            // The guard is NARROWED, not removed. cashBookId = null is the shop's fiscal
            // Ladenkasse: it signs, and signing needs a real terminal's clientId. Both of the
            // guard's branches must still fire on that path in NORMAL mode.
            tseMode = org.example.project.core.domain.enums.setup.TseMode.NORMAL
            val admin = assertFailsWith<PosException> { expenseIn(null, deviceId = "admin-web-abc123") }
            assertEquals(PosErrorCode.AUTH_FORBIDDEN, admin.code)

            val unregistered = assertFailsWith<PosException> { expenseIn(null, deviceId = "no-such-terminal") }
            assertEquals(PosErrorCode.CARD_TERMINAL_NOT_FOUND, unregistered.code)

            // Trailing Unit: JUnit5 silently SKIPS an expression-bodied @Test returning non-void.
            Unit
        }

    @Test
    fun `with the TSE disabled a FISCAL entry needs no terminal, and nothing is signed or queued`() =
        runBlocking {
            // Nothing is signed when disabled, so the adminApp (admin-web-*) and an unknown device
            // may book a manual Kassenbuch entry. The signing queue is a throwing proxy: any enqueue
            // from the placeholder client id would fail this test.
            expenseIn(null, deviceId = "admin-web-abc123")
            expenseIn(null, deviceId = "no-such-terminal")

            transaction {
                val header = lastHeader()
                assertEquals(TseSigningStatus.DISABLED, header.tseSigningStatus)
                assertNull(header.tseTransaction)
                assertEquals(true, header.cashBook.isFiscal)
            }
            Unit
        }

    /**
     * Correcting a non-fiscal entry keeps BOTH legs in that book.
     *
     * The reversal request is built inside `createCorrection`, and it used to omit `cashBookId`.
     * `createSignedEntry` reads a null book as "the shop's fiscal Ladenkasse", so correcting a
     * Tresor entry booked its `CORRECTION_IN` into the FISCAL book while the original stayed
     * un-reversed in the Tresor: both books wrong, and a phantom credit in the one that feeds
     * DSFinV-K. Measured on restored production data before the fix — Tresor was debited twice and
     * never credited back, Ladenkasse gained 15.00 out of nowhere.
     *
     * `correctedEntry.cashBookId` is deliberately **null** here: that is what a caller who does not
     * think about books sends, and it must still land in the original's book rather than default to
     * the fiscal one. The assertion is on every header that exists afterwards, not just the two the
     * call returns, so a leg escaping into another book cannot hide.
     */
    @Test
    fun `correcting a non-fiscal entry keeps both legs out of the fiscal book`() =
        runBlocking {
            val tresor = tresorId()
            expenseIn(tresor)
            val originalId = transaction { lastHeader().id.value }

            service.createCorrection(
                employeeId = emp,
                deviceId = DEV,
                headerId = originalId,
                request =
                    KassenbuchDto.CorrectEntryRequestDto(
                        reason = "Betrag falsch erfasst",
                        correctedEntry =
                            KassenbuchDto.CreateKassenbuchEntryRequestDto(
                                entryType = KassenbuchEntryType.CASH_EXPENSE,
                                lines = listOf(KassenbuchDto.EntryLineDto(vatRate = "19", amount = "42.00")),
                                belegtext = "Test-Ausgabe korrigiert",
                                cashBookId = null,
                            ),
                    ),
            )

            val booksOfEveryHeader =
                transaction {
                    KassenbuchHeader.all().map { it.belegNr to it.cashBook.id.value }
                }

            assertEquals(
                4,
                booksOfEveryHeader.size,
                // F59: plus expenseIn's funding deposit, which sits in the Tresor too.
                "expected the funding deposit, the original, its reversal and the corrected re-entry: $booksOfEveryHeader",
            )
            assertEquals(
                emptyList(),
                booksOfEveryHeader.filter { (_, bookId) -> bookId != tresor },
                "every leg of a non-fiscal correction must stay in that book, but these did not",
            )
        }
}

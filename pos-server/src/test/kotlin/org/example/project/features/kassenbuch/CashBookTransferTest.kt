package org.example.project.features.kassenbuch

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.example.project.core.domain.dto.document.DocumentDto
import org.example.project.core.domain.dto.kassenbuch.CashBookDto
import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.enums.kassenbuch.CorrectionRole
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.core.domain.enums.tse.TseSigningStatus
import org.example.project.core.error.PosErrorCode
import org.example.project.features.audit.domain.models.TransactionAuditLog
import org.example.project.features.document.domain.models.Document
import org.example.project.features.document.domain.service.DocumentManagementService
import org.example.project.features.document.domain.service.PdfGenerationService
import org.example.project.features.employee.data.repository.EmployeeRepositoryImpl
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.kassenbuch.data.repository.CashBookRepositoryImpl
import org.example.project.features.kassenbuch.data.repository.KassenbuchRepositoryImpl
import org.example.project.features.kassenbuch.data.service.CashBookTransferServiceImpl
import org.example.project.features.kassenbuch.data.service.KassenbuchServiceImpl
import org.example.project.features.kassenbuch.data.service.KassenbuchTseSigner
import org.example.project.features.kassenbuch.database.tables.KassenbuchHeaderTable
import org.example.project.features.kassenbuch.domain.models.KassenbuchHeader
import org.example.project.features.setup.data.repository.ShopInfoRepositoryImpl
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.terminal.data.repository.TerminalRepositoryImpl
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.features.tse.data.service.TseVorgangSigner
import org.example.project.features.tse.domain.service.TseSigningQueueService
import org.example.project.features.zBon.data.repository.ZBonRepositoryImpl
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.example.project.testutils.testKassenbuchDocuments
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@DisplayName("Book-to-book transfers are atomic pairs")
class CashBookTransferTest {
    // === fixture copied verbatim from KassenbuchSignedEntryServiceTest / NonFiscalSigningTest ===
    private val DEV = "test-device"

    private lateinit var service: KassenbuchServiceImpl
    private lateinit var repository: KassenbuchRepositoryImpl
    private lateinit var shop: ShopInfo
    private lateinit var employee: Employee
    private lateinit var emp: String

    private lateinit var transferService: CashBookTransferServiceImpl
    private val books = CashBookRepositoryImpl()

    private var kasseId: Long = 0
    private var tresorId: Long = 0

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

        transferService =
            CashBookTransferServiceImpl(
                configManager = disabledTseConfigManager,
                cashBookRepository = books,
                kassenbuchRepository = repository,
                employeeRepository = EmployeeRepositoryImpl(),
                terminalRepository = TerminalRepositoryImpl(),
                documents = testKassenbuchDocuments(pdfGenerationService = fakePdfGenerationService),
                // The extracted signing collaborator, built from the same DISABLED-TSE config the
                // rest of this fixture uses, so the fiscal leg still exercises the real decision path.
                tseSigner =
                    KassenbuchTseSigner(
                        configManager = disabledTseConfigManager,
                        tseServiceFactory = tseFactory,
                        tseSigningQueueService = noopProxy<TseSigningQueueService>(),
                    ),
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
            throw NotImplementedError("Stub: ${T::class.simpleName}.${method.name} should not be called in transfer tests")
        } as T
    }

    /**
     * Config manager stub: TSE is DISABLED (mode=DISABLED, failureBehavior=QUEUE), which is the
     * scenario these transfer tests run under (the real TseServiceFactory's signTransaction
     * returns null -> resolver yields DISABLED).
     */
    private val disabledTseConfigManager: org.example.project.core.config.IShopConfigurationManager by lazy {
        @Suppress("UNCHECKED_CAST")
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
                "getDefaultCurrency" -> "EUR"
                else -> throw NotImplementedError(
                    "Stub: IShopConfigurationManager.${method.name} should not be called in transfer tests",
                )
            }
        } as org.example.project.core.config.IShopConfigurationManager
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
                val id = newDocumentId("transfer-eigenbeleg.pdf")
                DocumentDto.DocumentResponseDto(
                    id = id,
                    filename = "transfer-eigenbeleg.pdf",
                    fileType = "INVOICE",
                    fileSize = 1024L,
                    uploadedAt = "2026-07-19T00:00:00",
                    uploadedBy = emp,
                    downloadUrl = "/download/$id",
                    formattedFileSize = "1.0 KB",
                    monthFolder = "2026-07",
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

    /** Seed the Ladenkasse so it actually holds cash to move. */
    private fun seedKasse(amount: String) {
        val docId = newDocumentId("seed.pdf")
        transaction {
            val header =
                repository.createEntry(
                    shop = shop,
                    employee = employee,
                    headerData =
                        KassenbuchDto.KassenbuchData(
                            entryType = KassenbuchEntryType.CASH_DEPOSIT,
                            belegNr = repository.nextBelegNrBlocking(kasseId),
                            vorzBetrag = amount,
                            belegDatum = "19.07.2026",
                            belegtext = "Anfangsbestand",
                            cashBookId = kasseId,
                        ),
                    lineItemsData = listOf(KassenbuchDto.LineItemData(vorzBetrag = amount)),
                )
            repository.linkDocument(header.id.value, docId)
        }
    }

    private fun setUpBooks() {
        transaction {
            kasseId = books.fiscalBookFor(shop).id.value
            tresorId = books.create(shop, "Tresor", isFiscal = false, datevAccount = "1010").id.value
        }
        seedKasse("2000.00")
    }

    private suspend fun transfer(amount: String = "1200.00") =
        transferService.createTransfer(
            employeeId = emp,
            deviceId = DEV,
            request =
                CashBookDto.CreateTransferRequestDto(
                    fromBookId = kasseId,
                    toBookId = tresorId,
                    amount = amount,
                    belegDatum = "19.07.2026",
                    note = "Tageseinnahme",
                ),
        )

    private fun legs(transferId: String): Pair<KassenbuchHeader, KassenbuchHeader> =
        transaction {
            val both = KassenbuchHeader.find { KassenbuchHeaderTable.transferId eq transferId }.toList()
            assertEquals(2, both.size, "a transfer is exactly two legs")
            val out = both.first { it.entryType == KassenbuchEntryType.CASH_TRANSFER_OUT }
            val into = both.first { it.entryType == KassenbuchEntryType.CASH_TRANSFER_IN }
            out to into
        }

    @Test
    fun `a transfer creates two legs sharing a transferId and pointing at each other`() =
        runBlocking {
            setUpBooks()
            val result = transfer()
            val (out, into) = legs(result.transferId)

            transaction {
                assertEquals(0, BigDecimal("-1200.00").compareTo(out.totalAmount))
                assertEquals(0, BigDecimal("1200.00").compareTo(into.totalAmount))
                assertEquals(tresorId, out.counterpartyBook?.id?.value)
                assertEquals(kasseId, into.counterpartyBook?.id?.value)
            }
        }

    @Test
    fun `each leg draws its belegNr from its own book series`() =
        runBlocking {
            setUpBooks()
            val (out, into) = legs(transfer().transferId)

            transaction {
                assertNotEquals(out.belegNr, into.belegNr, "each cash book keeps its own series")
                // The Tresor has no prior entry, so its first Beleg is sequence 0001.
                assertEquals(1, into.belegNr.substringAfterLast('-').toInt())
            }
        }

    @Test
    fun `each leg gets its own Eigenbeleg`() =
        runBlocking {
            setUpBooks()
            val (out, into) = legs(transfer().transferId)

            transaction {
                assertNotNull(out.linkedDocumentId, "documents are mandatory on every entry")
                assertNotNull(into.linkedDocumentId)
                // linkedDocumentId is uniqueIndex — the legs CANNOT share one document.
                assertNotEquals(out.linkedDocumentId, into.linkedDocumentId)
            }
        }

    @Test
    fun `the fiscal leg is signed and the safe leg is not`() =
        runBlocking {
            setUpBooks()
            val (out, into) = legs(transfer().transferId)

            transaction {
                assertNotEquals(TseSigningStatus.NOT_APPLICABLE, out.tseSigningStatus)
                assertEquals(TseSigningStatus.NOT_APPLICABLE, into.tseSigningStatus)
                assertNull(into.tseTransaction)
            }
        }

    @Test
    fun `balances move in opposite directions by the same amount`() =
        runBlocking {
            setUpBooks()
            transfer()

            transaction {
                assertEquals(0, BigDecimal("800.00").compareTo(repository.getCurrentBalance(shop, kasseId)))
                assertEquals(0, BigDecimal("1200.00").compareTo(repository.getCurrentBalance(shop, tresorId)))
            }
        }

    @Test
    fun `a transfer exceeding the source balance books neither leg`() =
        runBlocking {
            setUpBooks()

            assertFailsWith<PosException> { transfer("5000.00") }

            transaction {
                assertEquals(0, BigDecimal("2000.00").compareTo(repository.getCurrentBalance(shop, kasseId)))
                assertEquals(0, BigDecimal.ZERO.compareTo(repository.getCurrentBalance(shop, tresorId)))
                assertTrue(
                    KassenbuchHeader.all().none { it.transferId != null },
                    "a refused transfer must leave no half-booked leg",
                )
            }
            Unit
        }

    @Test
    fun `the over-balance refusal names both amounts at scale 2, not the stored scale 4`() =
        runBlocking {
            setUpBooks()

            val refusal = assertFailsWith<PosException> { transfer("5000.00") }

            // The balance is read back from a DECIMAL(19,4) column; interpolating the BigDecimal
            // directly read "holds 2000.0000" (checklist 12.9, live: "holds 35.8100").
            // F59: the transfer guard is now THE Kassenminus guard — typed 409, one wording for every
            // withdrawal (it was 422 VALIDATION_FAILED "… cannot transfer …").
            assertEquals(HttpStatusCode.Conflict, refusal.httpStatus)
            assertEquals(PosErrorCode.KASSENBUCH_INSUFFICIENT_CASH, refusal.code)
            assertEquals("Cash book 'Ladenkasse' holds 2000.00 — cannot pay out 5000.00", refusal.message)
        }

    /** Minimal-but-valid correction request: the guard fires before this body is ever inspected. */
    private fun correctionRequest(): KassenbuchDto.CorrectEntryRequestDto =
        KassenbuchDto.CorrectEntryRequestDto(
            reason = "attempted single-leg correction",
            correctedEntry =
                KassenbuchDto.CreateKassenbuchEntryRequestDto(
                    entryType = KassenbuchEntryType.CASH_TRANSFER_OUT,
                    lines = listOf(KassenbuchDto.EntryLineDto(amount = "-1200.00")),
                    belegtext = "irrelevant",
                ),
        )

    @Test
    fun `correcting a single transfer leg is refused`() =
        runBlocking {
            setUpBooks()
            val result = transfer()

            // If the guard regressed (removed, or fires on the wrong condition), createCorrection
            // would proceed into the normal correction flow instead of throwing here — either no
            // exception at all (assertFailsWith fails) or a PosException whose message never
            // mentions the reverse path (the assertTrue below fails).
            val exOut =
                assertFailsWith<PosException> {
                    service.createCorrection(emp, DEV, result.fromLegHeaderId, correctionRequest())
                }
            assertTrue(
                exOut.message!!.contains("reverse", ignoreCase = true),
                "message must name the reverse path, was: ${exOut.message}",
            )

            val exIn =
                assertFailsWith<PosException> {
                    service.createCorrection(emp, DEV, result.toLegHeaderId, correctionRequest())
                }
            assertTrue(exIn.message!!.contains("reverse", ignoreCase = true))

            transaction {
                assertTrue(
                    KassenbuchHeader.find { KassenbuchHeaderTable.correctsHeaderId eq result.fromLegHeaderId }.empty(),
                    "a refused correction must create nothing",
                )
            }
            Unit
        }

    @Test
    fun `reverseTransfer stornos both legs and restores both balances`() =
        runBlocking {
            setUpBooks()
            val result = transfer()

            val reversal =
                transferService.reverseTransfer(
                    employeeId = emp,
                    deviceId = DEV,
                    transferId = result.transferId,
                    reason = "Rückbuchung wegen Fehlbuchung",
                )

            assertNotEquals(result.transferId, reversal.transferId, "the reversal pair gets a FRESH transferId")

            transaction {
                val reversalHeaders = KassenbuchHeader.find { KassenbuchHeaderTable.transferId eq reversal.transferId }.toList()
                assertEquals(2, reversalHeaders.size, "a reversal is exactly two legs")
                assertTrue(reversalHeaders.all { it.correctionRole == CorrectionRole.REVERSAL })

                val reversalOfOut = reversalHeaders.first { it.cashBook.id.value == kasseId }
                val reversalOfIn = reversalHeaders.first { it.cashBook.id.value == tresorId }

                assertEquals(KassenbuchEntryType.CASH_TRANSFER_IN, reversalOfOut.entryType)
                assertEquals(0, BigDecimal("1200.00").compareTo(reversalOfOut.totalAmount))
                assertEquals(result.fromLegHeaderId, reversalOfOut.correctsHeader?.id?.value)

                assertEquals(KassenbuchEntryType.CASH_TRANSFER_OUT, reversalOfIn.entryType)
                assertEquals(0, BigDecimal("-1200.00").compareTo(reversalOfIn.totalAmount))
                assertEquals(result.toLegHeaderId, reversalOfIn.correctsHeader?.id?.value)

                // Original legs stay exactly as they are — GoBD never edits/deletes a booking.
                assertEquals(0, BigDecimal("-1200.00").compareTo(KassenbuchHeader.findById(result.fromLegHeaderId)!!.totalAmount))
                assertEquals(0, BigDecimal("1200.00").compareTo(KassenbuchHeader.findById(result.toLegHeaderId)!!.totalAmount))

                assertEquals(0, BigDecimal("2000.00").compareTo(repository.getCurrentBalance(shop, kasseId)))
                assertEquals(0, BigDecimal.ZERO.compareTo(repository.getCurrentBalance(shop, tresorId)))
            }
        }

    @Test
    fun `reversing an already-reversed transfer is refused`() =
        runBlocking {
            setUpBooks()
            val result = transfer()
            transferService.reverseTransfer(emp, DEV, result.transferId, "first reversal")

            // If the double-reversal guard regressed, this second call would silently create a
            // THIRD pair (duplicating money back into the Ladenkasse) instead of throwing.
            val ex =
                assertFailsWith<PosException> {
                    transferService.reverseTransfer(emp, DEV, result.transferId, "second reversal")
                }
            assertTrue(ex.message!!.contains("already", ignoreCase = true), "was: ${ex.message}")

            transaction {
                assertEquals(0, BigDecimal("2000.00").compareTo(repository.getCurrentBalance(shop, kasseId)))
                assertEquals(0, BigDecimal.ZERO.compareTo(repository.getCurrentBalance(shop, tresorId)))
            }
        }

    private fun auditRows(operation: String) =
        transaction {
            TransactionAuditLog.all().filter { it.operation == operation }.map { Triple(it.entityType, it.entityId, it.entityName) }
        }

    // admin20 NOTE-C: a Geldtransit and its reversal wrote no transaction_audit_log row at all.
    @Test
    fun `a transfer and its reversal are audited, and a refused reversal names the transfer`() =
        runBlocking {
            setUpBooks()
            val result = transfer()
            assertEquals(
                listOf(Triple("KASSENBUCH", result.transferId, "${result.fromBelegNr} / ${result.toBelegNr}")),
                auditRows("CREATE_CASH_TRANSFER"),
            )

            val reversal = transferService.reverseTransfer(emp, DEV, result.transferId, "Fehlbuchung")
            assertEquals(
                listOf(Triple("KASSENBUCH", reversal.transferId, "${reversal.fromBelegNr} / ${reversal.toBelegNr}")),
                auditRows("REVERSE_CASH_TRANSFER"),
            )

            assertFailsWith<PosException> { transferService.reverseTransfer(emp, DEV, result.transferId, "again") }
            val rows = auditRows("REVERSE_CASH_TRANSFER")
            assertEquals(2, rows.size, "the refusal writes its own row: $rows")
            assertTrue(rows.any { it.second == result.transferId }, "the refused row names the transfer it tried to reverse: $rows")
            Unit
        }

    // emu21 NOTE-u: after_state held the DTO's Kotlin toString(), not JSON.
    @Test
    fun `every transfer audit row carries JSON or null as its before and after state`() =
        runBlocking {
            setUpBooks()
            val result = transfer()
            transferService.reverseTransfer(emp, DEV, result.transferId, "Fehlbuchung")
            assertFailsWith<PosException> { transferService.reverseTransfer(emp, DEV, result.transferId, "again") }

            val states =
                transaction {
                    TransactionAuditLog
                        .all()
                        .filter { it.operation in setOf("CREATE_CASH_TRANSFER", "REVERSE_CASH_TRANSFER") }
                        .map { Triple(it.operation, it.beforeState, it.afterState) }
                }
            assertEquals(3, states.size, "$states")
            states.forEach { (operation, before, after) ->
                listOf(before, after).filterNotNull().forEach { state ->
                    assertTrue(runCatching { Json.parseToJsonElement(state) is JsonObject }.getOrDefault(false), "$operation state is not JSON: $state")
                }
            }
            val created = states.first { it.first == "CREATE_CASH_TRANSFER" }.third.orEmpty()
            assertTrue(created.contains("\"transferId\":\"${result.transferId}\""), created)
            Unit
        }

    @Test
    fun `each reversal leg gets its own Storno-Eigenbeleg`() =
        runBlocking {
            setUpBooks()
            val result = transfer()
            val reversal = transferService.reverseTransfer(emp, DEV, result.transferId, "reason")

            transaction {
                val reversalHeaders = KassenbuchHeader.find { KassenbuchHeaderTable.transferId eq reversal.transferId }.toList()
                assertEquals(2, reversalHeaders.size)
                val docIds = reversalHeaders.map { it.linkedDocumentId }
                assertTrue(docIds.all { it != null }, "documents are mandatory on every entry")
                assertNotEquals(docIds[0], docIds[1], "linkedDocumentId is uniqueIndex — legs cannot share one document")
            }
        }
}

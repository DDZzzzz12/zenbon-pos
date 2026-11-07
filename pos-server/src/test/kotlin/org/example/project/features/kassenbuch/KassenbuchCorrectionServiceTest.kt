package org.example.project.features.kassenbuch

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.document.DocumentDto
import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.enums.kassenbuch.CorrectionRole
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.core.domain.enums.tse.TseSigningStatus
import org.example.project.features.document.domain.models.Document
import org.example.project.features.document.domain.service.DocumentManagementService
import org.example.project.features.document.domain.service.PdfGenerationService
import org.example.project.features.employee.data.repository.EmployeeRepositoryImpl
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.kassenbuch.data.repository.KassenbuchRepositoryImpl
import org.example.project.features.kassenbuch.data.service.KassenbuchServiceImpl
import org.example.project.features.setup.data.repository.ShopInfoRepositoryImpl
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.terminal.data.repository.TerminalRepositoryImpl
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.features.tse.data.service.TseVorgangSigner
import org.example.project.features.zBon.data.repository.ZBonRepositoryImpl
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.example.project.testutils.testKassenbuchDocuments
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@DisplayName("KassenbuchServiceImpl.createCorrection")
class KassenbuchCorrectionServiceTest {
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
                "getTseMode" -> org.example.project.core.domain.enums.setup.TseMode.DISABLED
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

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private var belegCounter = 0

    @Suppress("DefaultLocale")
    private fun seedOriginal(gross: String = "100.0"): Long {
        val docId = newDocumentId("original.pdf")
        val belegNr = "2026-06-${String.format("%04d", ++belegCounter)}"
        val headerId =
            transaction {
                val header =
                    repository.createEntry(
                        shop = shop,
                        employee = employee,
                        headerData =
                            KassenbuchDto.KassenbuchData(
                                entryType = KassenbuchEntryType.CASH_SALE,
                                belegNr = belegNr,
                                vorzBetrag = gross,
                                belegDatum = "24.06.2026",
                                belegtext = "Original entry",
                            ),
                        lineItemsData = listOf(KassenbuchDto.LineItemData(vorzBetrag = gross, ustSatz = "19")),
                    )
                repository.linkDocument(header.id.value, docId)
                header.id.value
            }
        return headerId
    }

    private fun correctedEntryRequest(gross: String = "90.0") =
        KassenbuchDto.CreateKassenbuchEntryRequestDto(
            entryType = KassenbuchEntryType.CASH_SALE,
            lines = listOf(KassenbuchDto.EntryLineDto(vatRate = "19", amount = gross)),
            belegtext = "Corrected entry",
            documentId = null,
        )

    private fun correctionRequest(reason: String) =
        KassenbuchDto.CorrectEntryRequestDto(
            reason = reason,
            correctedEntry = correctedEntryRequest(),
        )

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    fun `correction reverses original and re-books corrected, original untouched`() =
        runBlocking {
            val originalId = seedOriginal(gross = "100.0")

            val detail =
                service.createCorrection(
                    employeeId = emp,
                    deviceId = DEV,
                    headerId = originalId,
                    request =
                        KassenbuchDto.CorrectEntryRequestDto(
                            reason = "Wrong amount",
                            correctedEntry = correctedEntryRequest(gross = "90.0"),
                        ),
                )

            // Returned detail is the corrected RE_ENTRY, linked back to the original.
            assertEquals(originalId, detail.correctsHeaderId)
            assertEquals(CorrectionRole.RE_ENTRY, detail.correctionRole)

            transaction {
                val corrections = repository.findCorrectionsFor(originalId)
                assertEquals(2, corrections.size)
                assertEquals(
                    setOf(CorrectionRole.REVERSAL, CorrectionRole.RE_ENTRY),
                    corrections.mapNotNull { it.correctionRole }.toSet(),
                )

                // Original is unchanged: still a normal entry with no correction role.
                val reloaded = repository.getEntryById(originalId)
                assertNotNull(reloaded)
                assertNull(reloaded.correctionRole)

                // Regression: TSE is DISABLED here, so signing must NOT mark entries SIGNED.
                // The resolver yields DISABLED for a null signature — the live-bug fix.
                val reEntry = repository.getEntryById(detail.headerId)!!
                assertEquals(TseSigningStatus.DISABLED, reEntry.tseSigningStatus)
                corrections.forEach {
                    assertEquals(TseSigningStatus.DISABLED, it.tseSigningStatus)
                }
            }

            // The detail view surfaces alreadyReversed so the client can disable re-correction.
            val originalDetail = service.getGroupedEntryDetail(originalId)
            assertTrue(originalDetail.alreadyReversed)
            assertNull(originalDetail.correctionRole)
            assertFalse(detail.alreadyReversed)
        }

    @Test
    fun `double correction is rejected`() =
        runBlocking {
            val originalId = seedOriginal(gross = "100.0")
            service.createCorrection(emp, DEV, originalId, correctionRequest("first"))
            assertFailsWith<PosException> {
                service.createCorrection(emp, DEV, originalId, correctionRequest("second"))
            }
            Unit
        }

    @Test
    fun `blank reason is rejected`() =
        runBlocking {
            val originalId = seedOriginal(gross = "100.0")
            assertFailsWith<PosException> {
                service.createCorrection(emp, DEV, originalId, correctionRequest(""))
            }
            Unit
        }

    @Test
    fun `cannot unlink or replace document on a correction entry`() =
        runBlocking {
            val originalId = seedOriginal(gross = "100.0")
            // The returned detail is the corrected RE_ENTRY (a correction entry).
            val reEntryId =
                service
                    .createCorrection(
                        emp,
                        DEV,
                        originalId,
                        KassenbuchDto.CorrectEntryRequestDto(
                            reason = "Wrong amount",
                            correctedEntry = correctedEntryRequest(gross = "90.0"),
                        ),
                    ).headerId

            // The correctionRole guard fires before any repository call, so the dummy doc id is unused.
            assertFailsWith<PosException> { service.replaceDocument(emp, DEV, reEntryId, 999L) }
            assertFailsWith<PosException> { service.unlinkDocument(emp, DEV, reEntryId) }
            Unit
        }

    @Test
    fun `corrected re-entry can be corrected again (chain) but a Storno cannot`() =
        runBlocking {
            val originalId = seedOriginal(gross = "100.0")
            val reEntryId =
                service
                    .createCorrection(
                        emp,
                        DEV,
                        originalId,
                        KassenbuchDto.CorrectEntryRequestDto(
                            reason = "first",
                            correctedEntry = correctedEntryRequest(gross = "90.0"),
                        ),
                    ).headerId
            val reversalId =
                transaction {
                    repository
                        .findCorrectionsFor(originalId)
                        .first { it.correctionRole == CorrectionRole.REVERSAL }
                        .id.value
                }

            // Correcting the RE_ENTRY chains another Storno + re-entry referencing the re-entry.
            val chained =
                service.createCorrection(
                    emp,
                    DEV,
                    reEntryId,
                    KassenbuchDto.CorrectEntryRequestDto(
                        reason = "second",
                        correctedEntry = correctedEntryRequest(gross = "95.0"),
                    ),
                )
            assertEquals(reEntryId, chained.correctsHeaderId)
            assertEquals(CorrectionRole.RE_ENTRY, chained.correctionRole)
            transaction {
                assertTrue(repository.hasReversal(reEntryId))
                assertEquals(2, repository.findCorrectionsFor(reEntryId).size)
            }

            // A pure Storno (REVERSAL) can never be corrected.
            assertFailsWith<PosException> {
                service.createCorrection(emp, DEV, reversalId, correctionRequest("nope"))
            }
            Unit
        }
}

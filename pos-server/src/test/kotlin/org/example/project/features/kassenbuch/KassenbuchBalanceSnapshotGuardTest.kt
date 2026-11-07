package org.example.project.features.kassenbuch

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.document.DocumentDto
import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
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
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Round-3 KABS-3 (Anhang C p. 56): balance-snapshot entry types are guarded at the SERVICE level.
 *
 * - CLOSING_BALANCE is rejected outright (a count is the Kassensturz/DifferenzSollIst flow's job).
 * - OPENING_BALANCE is only a genuine Anfangsbestand on a virgin book: zero running balance AND no
 *   prior ZBon closing.
 *
 * (Client mirror of these guards is a separate follow-up.)
 */
@DisplayName("Kassenbuch balance-snapshot creation guards (KABS-3)")
class KassenbuchBalanceSnapshotGuardTest {
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

        // Real TseServiceFactory: with no global TSE config it falls back to DISABLED mode, whose
        // signTransaction returns null (no signing, no FAIL/QUEUE branch) — safe with noop proxies.
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
    // Fakes (mirror KassenbuchSignedEntryServiceTest wiring)
    // -------------------------------------------------------------------------

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
        @Suppress("UNCHECKED_CAST")
        Proxy.newProxyInstance(
            org.example.project.core.config.IShopConfigurationManager::class.java.classLoader,
            arrayOf(org.example.project.core.config.IShopConfigurationManager::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getTseMode" -> org.example.project.core.domain.enums.setup.TseMode.DISABLED
                // createEntry reads the shop's Beleg-age limit for its date guard.
                "getKassenbuchMaxBelegAgeDays" -> org.example.project.core.utils.KassenbuchBelegDateRule.DEFAULT_MAX_AGE_DAYS
                "getTseConfig" ->
                    org.example.project.core.domain.dto.tse.TseConfigDto.TseConfig(
                        timeAdminPin = "00000",
                    )
                else -> throw NotImplementedError(
                    "Stub: IShopConfigurationManager.${method.name} should not be called",
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

    private val fakePdfGenerationService: PdfGenerationService by lazy {
        @Suppress("UNCHECKED_CAST")
        Proxy.newProxyInstance(
            PdfGenerationService::class.java.classLoader,
            arrayOf(PdfGenerationService::class.java),
        ) { _, method, _ ->
            if (method.name == "generateEigenbelegPdf") {
                org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
                    .currentOrNull()
                    ?: error("generateEigenbelegPdf must be called within a transaction")
                val id = newDocumentId("eigenbeleg.pdf")
                DocumentDto.DocumentResponseDto(
                    id = id,
                    filename = "eigenbeleg.pdf",
                    fileType = "INVOICE",
                    fileSize = 1024L,
                    uploadedAt = "2026-07-16T00:00:00",
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

    private fun openingBalanceRequest(
        amount: String,
        docId: Long,
    ) = KassenbuchDto.CreateKassenbuchEntryRequestDto(
        entryType = KassenbuchEntryType.OPENING_BALANCE,
        lines = listOf(KassenbuchDto.EntryLineDto(vatRate = "0", amount = amount)),
        belegtext = "Anfangsbestand",
        documentId = docId,
    )

    private fun closingBalanceRequest(
        amount: String,
        docId: Long,
    ) = KassenbuchDto.CreateKassenbuchEntryRequestDto(
        entryType = KassenbuchEntryType.CLOSING_BALANCE,
        lines = listOf(KassenbuchDto.EntryLineDto(vatRate = "0", amount = amount)),
        belegtext = "Endbestand",
        documentId = docId,
    )

    /** Seed a positive-balance entry directly via the repository (bypasses the service guard). */
    private fun seedCashSale(amount: String) =
        transaction {
            repository.createEntry(
                shop = shop,
                employee = employee,
                headerData =
                    KassenbuchDto.KassenbuchData(
                        entryType = KassenbuchEntryType.CASH_SALE,
                        belegNr = "2026-07-9000",
                        vorzBetrag = amount,
                        belegDatum = "16.07.2026",
                        belegtext = "Barumsatz",
                    ),
                lineItemsData = listOf(KassenbuchDto.LineItemData(vorzBetrag = amount, ustSatz = "19")),
            )
        }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    fun `OPENING_BALANCE is rejected when the running balance is non-zero`() =
        runBlocking {
            seedCashSale("100.0")
            val docId = newDocumentId("anfang.pdf")

            val ex =
                assertFailsWith<PosException> {
                    service.createEntry(emp, DEV, openingBalanceRequest("50.0", docId))
                }
            assertEquals(HttpStatusCode.Conflict, ex.httpStatus)
            assertTrue(ex.message!!.contains("virgin"), "message points to the virgin-book precondition")
            Unit
        }

    @Test
    fun `OPENING_BALANCE is rejected when a prior ZBon closing exists`() =
        runBlocking {
            // Balance is still zero, but a prior day was already closed.
            TestData.createZBonFullDay(shop, employee, nummer = 1L)
            val docId = newDocumentId("anfang.pdf")

            val ex =
                assertFailsWith<PosException> {
                    service.createEntry(emp, DEV, openingBalanceRequest("50.0", docId))
                }
            assertEquals(HttpStatusCode.Conflict, ex.httpStatus)
            assertTrue(ex.message!!.contains("priorClosingExists=true"))
            Unit
        }

    @Test
    fun `CLOSING_BALANCE is always rejected and points to the Kassensturz flow`() =
        runBlocking {
            val docId = newDocumentId("endbestand.pdf")

            val ex =
                assertFailsWith<PosException> {
                    service.createEntry(emp, DEV, closingBalanceRequest("50.0", docId))
                }
            assertEquals(HttpStatusCode.Conflict, ex.httpStatus)
            assertTrue(
                ex.message!!.contains("Kassensturz") || ex.message!!.contains("DifferenzSollIst"),
                "message routes to the count/Kassensturz flow",
            )
            Unit
        }

    @Test
    fun `OPENING_BALANCE succeeds on a virgin book (zero balance, no prior closing)`() =
        runBlocking {
            val docId = newDocumentId("anfang.pdf")

            service.createEntry(emp, DEV, openingBalanceRequest("200.0", docId))

            transaction {
                val header =
                    KassenbuchHeader
                        .all()
                        .orderBy(KassenbuchHeaderTable.id to SortOrder.DESC)
                        .first()
                assertEquals(KassenbuchEntryType.OPENING_BALANCE, header.entryType)
                // A genuine opening establishes the starting cash: currentBalance = the fill amount.
                assertEquals(0, java.math.BigDecimal("200.00").compareTo(header.currentBalance))
            }
            Unit
        }
}

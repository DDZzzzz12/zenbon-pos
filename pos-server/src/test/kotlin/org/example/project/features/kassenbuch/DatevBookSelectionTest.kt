package org.example.project.features.kassenbuch

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.document.DocumentDto
import org.example.project.core.domain.dto.kassenbuch.CashBookDto
import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.features.document.domain.models.Document
import org.example.project.features.document.domain.service.PdfGenerationService
import org.example.project.features.employee.data.repository.EmployeeRepositoryImpl
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.kassenbuch.data.repository.CashBookRepositoryImpl
import org.example.project.features.kassenbuch.data.repository.KassenbuchRepositoryImpl
import org.example.project.features.kassenbuch.data.service.CashBookTransferServiceImpl
import org.example.project.features.kassenbuch.data.service.KassenbuchTseSigner
import org.example.project.features.kassenbuch.data.service.SplitTransferWarnings
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.terminal.data.repository.TerminalRepositoryImpl
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.features.tse.data.service.TseVorgangSigner
import org.example.project.features.tse.domain.service.TseSigningQueueService
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
import kotlin.test.assertTrue

/**
 * Task 25: optional cash-book selection for the DATEV exports.
 *
 * [KassenbuchRepository.getEntriesByDateRange][org.example.project.features.kassenbuch.domain.repository.KassenbuchRepository.getEntriesByDateRange]
 * is the single place that applies the `cashBookIds` filter, and
 * [SplitTransferWarnings] is the single place that detects a Geldtransit whose counterpart leg
 * was excluded by a partial selection — both are exercised directly here rather than through the
 * full `DatevXmlExportServiceImpl`/`DatevBuchungsstapelExportServiceImpl` pipeline.
 *
 * That pipeline's `generate()` calls the KOIN-backed `ShopConfigurationManager` singleton
 * (consultant/client numbers, export path) directly — see [DatevXmlLedgerAccountMappingTest],
 * the only other test that touches these services, which likewise stops at a pure mapping method
 * rather than constructing the full `export()` flow. Testing the filter and the warning helper at
 * their actual source is not a narrowed substitute for that: it is where Task 25's logic lives,
 * per the design note "the filter belongs in ONE place" and "one small helper... both export
 * services call".
 */
@DisplayName("DATEV exports can be scoped to a subset of cash books")
class DatevBookSelectionTest {
    private val DEV = "test-device"

    // A range wide enough to always contain "now" (createdAt is stamped at insert time via
    // currentTimeStamp(), not derived from belegDatum — see getEntriesByDateRange's own
    // comment), so the test is not tied to the date it happens to run on.
    private val wideStart = "2020-01-01"
    private val wideEnd = "2035-12-31"

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

        val tseFactory =
            org.example.project.features.tse.domain.factory.TseServiceFactory(
                tseTransactionRepository = noopProxy(),
                tseSignClient = noopProxy(),
                signer = TseVorgangSigner(noopProxy(), noopProxy(), noopProxy()),
            )

        transferService =
            CashBookTransferServiceImpl(
                configManager = disabledTseConfigManager,
                cashBookRepository = books,
                kassenbuchRepository = repository,
                employeeRepository = EmployeeRepositoryImpl(),
                terminalRepository = TerminalRepositoryImpl(),
                documents = testKassenbuchDocuments(pdfGenerationService = fakePdfGenerationService),
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

        transaction {
            kasseId = books.fiscalBookFor(shop).id.value
            tresorId = books.create(shop, "Tresor", isFiscal = false, datevAccount = "1010").id.value
        }
    }

    @AfterEach
    fun teardown() {
        TestDatabase.teardown()
    }

    // -------------------------------------------------------------------------
    // Fakes — copied from CashBookTransferTest, the only other suite that builds
    // CashBookTransferServiceImpl from scratch.
    // -------------------------------------------------------------------------

    private inline fun <reified T : Any> noopProxy(): T {
        @Suppress("UNCHECKED_CAST")
        return Proxy.newProxyInstance(
            T::class.java.classLoader,
            arrayOf(T::class.java),
        ) { _, method, _ ->
            throw NotImplementedError("Stub: ${T::class.simpleName}.${method.name} should not be called in book-selection tests")
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
                "getTseConfig" ->
                    org.example.project.core.domain.dto.tse.TseConfigDto.TseConfig(
                        timeAdminPin = "00000",
                    )
                "getDefaultCurrency" -> "EUR"
                else -> throw NotImplementedError(
                    "Stub: IShopConfigurationManager.${method.name} should not be called in book-selection tests",
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
                val id = newDocumentId("book-selection-eigenbeleg.pdf")
                DocumentDto.DocumentResponseDto(
                    id = id,
                    filename = "book-selection-eigenbeleg.pdf",
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

    // === end of copied fixture ===

    private fun seedEntry(
        cashBookId: Long,
        amount: String = "50.00",
    ) {
        transaction {
            repository.createEntry(
                shop = shop,
                employee = employee,
                headerData =
                    KassenbuchDto.KassenbuchData(
                        entryType = KassenbuchEntryType.CASH_DEPOSIT,
                        belegNr = repository.nextBelegNrBlocking(cashBookId),
                        vorzBetrag = amount,
                        belegDatum = "19.07.2026",
                        belegtext = "Book-selection fixture entry",
                        cashBookId = cashBookId,
                    ),
                lineItemsData = listOf(KassenbuchDto.LineItemData(vorzBetrag = amount)),
            )
        }
    }

    /** Seed the Ladenkasse so a transfer out of it has something to move. */
    private fun seedKasseBalance(amount: String) = seedEntry(kasseId, amount)

    private suspend fun transfer(amount: String = "300.00") =
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

    @Test
    fun `the default export covers all books`() {
        seedEntry(kasseId)
        seedEntry(tresorId)

        transaction {
            val headers = repository.getEntriesByDateRange(shop, wideStart, wideEnd)
            assertTrue(headers.any { it.cashBook.id.value == kasseId }, "Ladenkasse entry must be present")
            assertTrue(headers.any { it.cashBook.id.value == tresorId }, "Tresor entry must be present")
        }
    }

    @Test
    fun `cashBookIds filters to the selected books`() {
        seedEntry(kasseId)
        seedEntry(tresorId)

        transaction {
            val headers = repository.getEntriesByDateRange(shop, wideStart, wideEnd, cashBookIds = listOf(tresorId))
            assertTrue(headers.isNotEmpty(), "the Tresor entry must be present")
            assertTrue(
                headers.all { it.cashBook.id.value == tresorId },
                "no Ladenkasse row may leak through a Tresor-only selection",
            )
        }
    }

    @Test
    fun `a partial selection that splits a transfer pair is reported`() =
        runBlocking {
            seedKasseBalance("2000.00")
            transfer()

            transaction {
                val kasseOnly = repository.getEntriesByDateRange(shop, wideStart, wideEnd, cashBookIds = listOf(kasseId))
                // If the cashBookIds filter regressed to "ignore the argument", both transfer legs
                // (and the seed deposit) would come back and this would fail: exactly one of the
                // two transfer legs must survive a Ladenkasse-only selection.
                val transferLegsInSelection = kasseOnly.filter { it.transferId != null }
                assertEquals(1, transferLegsInSelection.size, "only the Ladenkasse leg of the transfer should be in range")

                val warnings = SplitTransferWarnings.detect(kasseOnly)
                // If the warning helper regressed (removed, or its transferId grouping broken),
                // this would come back empty and the test would fail here.
                assertTrue(warnings.isNotEmpty(), "a split transfer must be reported")
                assertTrue(
                    warnings.any { it.contains("1460") },
                    "the warning must name the DATEV transit account 1460, was: $warnings",
                )
            }
            Unit
        }

    @Test
    fun `a selection containing both legs produces no warning`() =
        runBlocking {
            seedKasseBalance("2000.00")
            transfer()

            transaction {
                val bothBooks =
                    repository.getEntriesByDateRange(shop, wideStart, wideEnd, cashBookIds = listOf(kasseId, tresorId))
                val transferLegsInSelection = bothBooks.filter { it.transferId != null }
                assertEquals(2, transferLegsInSelection.size, "both transfer legs must be in range")

                val warnings = SplitTransferWarnings.detect(bothBooks)
                // If the helper degenerated to "always warn when any transfer is present" rather
                // than counting legs actually present, this would come back non-empty and the
                // test would fail.
                assertTrue(warnings.isEmpty(), "both legs present — nothing should be flagged, was: $warnings")
            }
            Unit
        }
}

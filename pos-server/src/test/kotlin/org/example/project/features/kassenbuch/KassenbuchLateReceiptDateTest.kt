package org.example.project.features.kassenbuch

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.minus
import kotlinx.datetime.number
import kotlinx.datetime.plus
import org.example.project.core.config.IShopConfigurationManager
import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.dto.tse.TseConfigDto
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.error.PosErrorCode
import org.example.project.features.document.domain.models.Document
import org.example.project.features.document.domain.service.DocumentManagementService
import org.example.project.features.document.domain.service.PdfGenerationService
import org.example.project.features.employee.data.repository.EmployeeRepositoryImpl
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.kassenbuch.data.repository.CashBookRepositoryImpl
import org.example.project.features.kassenbuch.data.repository.KassenbuchRepositoryImpl
import org.example.project.features.kassenbuch.data.service.KassenbuchServiceImpl
import org.example.project.features.kassenbuch.domain.models.CashBook
import org.example.project.features.kassenbuch.domain.models.CashBookClose
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
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * A receipt keeps its own date; the booking time decides the period.
 *
 * `KassenbuchEntryGuards.requireOpenPeriod` refused any entry whose Beleg date fell on or before the
 * book's last close. For the fiscal Ladenkasse that close is the latest Z-Bon, compared by *date*,
 * so after a Z-Bon a cash expense dated the same day — or a receipt from yesterday handed in this
 * morning — could not be booked at all (E2E-2026-09-20 round 2, Q). Every other consumer already
 * assigns an entry to its period by `created_at`, the TSE-attested booking time: the Z-Bon sweep
 * takes every unswept entry, DSFinV-K selects by Z-Bon / `created_at`, the DATEV exports select by
 * `created_at` (`KassenbuchRepositoryImpl.getEntriesByDateRange`), and the Kassensturz locks by
 * `created_at`. The guard was the one place a typed date decided the period.
 *
 * The rule now: a Beleg date may lie inside a closed period — the entry lands in the open period by
 * its booking time and keeps its real date — but not in the future, and not further back than the
 * shop's limit (default 31 days). The limit replaces the old protection against `01.01.1999`.
 */
@DisplayName("Kassenbuch — late receipts keep their date and book into the open period")
class KassenbuchLateReceiptDateTest {
    private val dev = "late-receipt-device"
    private val today = currentTimeStamp().date

    /** The shop setting the fake config answers with; tests change it before calling the service. */
    private var maxAgeDays = 31

    private lateinit var service: KassenbuchServiceImpl
    private lateinit var shop: ShopInfo
    private lateinit var employee: Employee
    private lateinit var emp: String

    @BeforeEach
    fun setup() {
        TestDatabase.setup()
        service =
            KassenbuchServiceImpl(
                kassenbuchRepository = KassenbuchRepositoryImpl(testKassenbuchDocuments()),
                zBonRepository = ZBonRepositoryImpl(),
                employeeRepository = EmployeeRepositoryImpl(),
                shopInfoRepository = ShopInfoRepositoryImpl(),
                tseServiceFactory =
                    org.example.project.features.tse.domain.factory.TseServiceFactory(
                        tseTransactionRepository = stub(),
                        tseSignClient = stub(),
                        signer = TseVorgangSigner(stub(), stub(), stub()),
                    ),
                tseSigningQueueService = stub(),
                terminalRepository = TerminalRepositoryImpl(),
                documents =
                    testKassenbuchDocuments(
                        pdfGenerationService = stub<PdfGenerationService>(),
                        documentManagementService = stub<DocumentManagementService>(),
                    ),
                configManager = configManager,
            )
        shop = TestData.createShopInfo()
        employee = TestData.createAdmin()
        emp = employee.id.value.toString()
        // F59: the 7.50 expenses below need cash in the Ladenkasse (Kassenminus guard); what these
        // tests assert is the Beleg date, not the balance.
        TestData.fundCashBook("100.00")
        transaction {
            Terminal.new {
                this.deviceId = dev
                this.clientId = "client-late"
                this.isActive = true
                this.tseClientRegistered = true
                this.registeredAt = currentTimeStamp()
                this.lastSeenAt = currentTimeStamp()
            }
        }
    }

    @AfterEach
    fun teardown() = TestDatabase.teardown()

    @Test
    fun `after today's Z-Bon a cash expense dated today can still be booked`() {
        zBonClosedAt(LocalDateTime(today, LocalTime(0, 1)))

        val response = runBlocking { service.createEntry(emp, dev, expense(today)) }

        assertEquals(germanDate(today), response.entries.first().belegDatum, "the receipt keeps its real date")
    }

    @Test
    fun `a receipt from yesterday handed in after yesterday's Z-Bon keeps its date`() {
        val yesterday = today.minus(DatePeriod(days = 1))
        zBonClosedAt(LocalDateTime(yesterday, LocalTime(23, 0)))

        val response = runBlocking { service.createEntry(emp, dev, expense(yesterday)) }

        assertEquals(germanDate(yesterday), response.entries.first().belegDatum)
    }

    @Test
    fun `a Tresor receipt dated inside a counted period is booked into the open period`() {
        val tresor = transaction { CashBookRepositoryImpl().create(shop, "Tresor", isFiscal = false, datevAccount = "1010") }
        val periodEnd = today.minus(DatePeriod(days = 3))
        kassensturzThrough(tresor, periodEnd)
        TestData.fundCashBook("100.00", tresor.id.value) // F59: the Tresor pays the 7.50 out of what it holds

        val response =
            runBlocking {
                service.createEntry(emp, dev, expense(periodEnd.minus(DatePeriod(days = 1)), cashBookId = tresor.id.value))
            }

        assertEquals(germanDate(periodEnd.minus(DatePeriod(days = 1))), response.entries.first().belegDatum)
    }

    @Test
    fun `a receipt older than the shop's limit is refused`() {
        maxAgeDays = 10

        val ex =
            assertFailsWith<PosException> {
                runBlocking { service.createEntry(emp, dev, expense(today.minus(DatePeriod(days = 11)))) }
            }

        assertEquals(PosErrorCode.VALIDATION_FAILED, ex.code)
    }

    @Test
    fun `a receipt exactly at the shop's limit is accepted`() {
        maxAgeDays = 10

        runBlocking { service.createEntry(emp, dev, expense(today.minus(DatePeriod(days = 10)))) }
    }

    @Test
    fun `the default limit refuses a receipt older than a month`() {
        val ex =
            assertFailsWith<PosException> {
                runBlocking { service.createEntry(emp, dev, expense(today.minus(DatePeriod(days = 32)))) }
            }

        assertEquals(PosErrorCode.VALIDATION_FAILED, ex.code)
    }

    @Test
    fun `a future-dated receipt is still refused`() {
        val ex =
            assertFailsWith<PosException> {
                runBlocking { service.createEntry(emp, dev, expense(today.plus(DatePeriod(days = 1)))) }
            }

        assertEquals(PosErrorCode.VALIDATION_FAILED, ex.code)
    }

    // ---- fixtures ------------------------------------------------------------

    private var docCounter = 0

    private fun expense(
        date: LocalDate,
        cashBookId: Long? = null,
    ) = KassenbuchDto.CreateKassenbuchEntryRequestDto(
        entryType = KassenbuchEntryType.CASH_EXPENSE,
        lines = listOf(KassenbuchDto.EntryLineDto(vatRate = "19", amount = "7.50")),
        belegDatum = germanDate(date),
        belegtext = "Late receipt",
        documentId = newDocumentId(),
        cashBookId = cashBookId,
    )

    private fun newDocumentId(): Long =
        transaction {
            Document
                .new {
                    this.filename = "receipt-${++docCounter}.pdf"
                    this.fileType = "INVOICE"
                    this.filePath = "/nonexistent/receipt-$docCounter.pdf"
                    this.fileSize = 1024L
                    this.uploadedAt = currentTimeStamp()
                    this.uploadedBy = employee
                    this.isActive = true
                    this.contentHash = "hash-late-$docCounter"
                    this.mediaType = "application/pdf"
                }.id.value
        }

    private fun zBonClosedAt(end: LocalDateTime) {
        TestData.createZBon(shop, employee, start = LocalDateTime(end.date, LocalTime(0, 0)), end = end, nummer = 1L)
    }

    private fun kassensturzThrough(
        book: CashBook,
        periodEnd: LocalDate,
    ) = transaction {
        CashBookClose.new {
            this.cashBook = book
            this.periodStart = periodEnd.minus(DatePeriod(days = 7))
            this.periodEnd = periodEnd
            this.bookBalance = BigDecimal("100.00")
            this.countedBalance = BigDecimal("100.00")
            this.difference = BigDecimal.ZERO
            this.closedBy = employee
            this.closedAt = LocalDateTime(periodEnd, LocalTime(23, 59))
        }
    }

    private fun germanDate(d: LocalDate): String = "${d.day.toString().padStart(2, '0')}.${d.month.number.toString().padStart(2, '0')}.${d.year}"

    /** TSE DISABLED, EUR, and the Beleg age limit — nothing else may be asked of the config here. */
    private val configManager: IShopConfigurationManager by lazy {
        Proxy.newProxyInstance(
            IShopConfigurationManager::class.java.classLoader,
            arrayOf(IShopConfigurationManager::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getTseMode" -> TseMode.DISABLED
                "getTseConfig" -> TseConfigDto.TseConfig(timeAdminPin = "00000")
                "getDefaultCurrency" -> "EUR"
                "getKassenbuchMaxBelegAgeDays" -> maxAgeDays
                else -> throw NotImplementedError("IShopConfigurationManager.${method.name} should not be called")
            }
        } as IShopConfigurationManager
    }

    private inline fun <reified T : Any> stub(): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            throw NotImplementedError("${T::class.simpleName}.${method.name} should not be called")
        } as T
}

package org.example.project.features.zBon

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import org.example.project.core.config.IShopConfigurationManager
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.kassenbuch.data.repository.KassenbuchRepositoryImpl
import org.example.project.features.kassenbuch.data.service.CashBookTransferServiceImpl
import org.example.project.features.kassenbuch.data.service.KassenbuchTseSigner
import org.example.project.features.salesinvoice.data.service.SalesInvoiceBillInvoiceLink
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.tse.data.service.TseVorgangSigner
import org.example.project.features.tse.domain.factory.TseServiceFactory
import org.example.project.features.zBon.data.repository.ZBonRepositoryImpl
import org.example.project.features.zBon.data.service.ZBonServiceImpl
import org.example.project.features.zBon.domain.models.ZBon
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.example.project.testutils.atStoredPrecision
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

@DisplayName("ZBonServiceImpl getRecentReports")
class ZBonServiceRecentReportsTest {
    private lateinit var service: ZBonServiceImpl
    private lateinit var employee: Employee
    private lateinit var shop: ShopInfo

    @BeforeEach
    fun setup() {
        TestDatabase.setup()
        employee = TestData.createAdmin()
        shop = TestData.createShopInfo()

        service =
            ZBonServiceImpl(
                repository = ZBonRepositoryImpl(),
                kassenbuchRepository = noopProxy(),
                cashBookRepository = noopProxy(),
                cashBookTransferService =
                    CashBookTransferServiceImpl(
                        configManager = simpleConfigManager(),
                        cashBookRepository = noopProxy(),
                        kassenbuchRepository = KassenbuchRepositoryImpl(testKassenbuchDocuments()),
                        employeeRepository = noopProxy(),
                        terminalRepository = noopProxy(),
                        documents = testKassenbuchDocuments(pdfGenerationService = noopProxy()),
                        tseSigner =
                            KassenbuchTseSigner(
                                configManager = simpleConfigManager(),
                                tseServiceFactory =
                                    TseServiceFactory(
                                        tseTransactionRepository = noopProxy(),
                                        tseSignClient = noopProxy(),
                                        signer = TseVorgangSigner(noopProxy(), noopProxy(), noopProxy()),
                                    ),
                                tseSigningQueueService = noopProxy(),
                            ),
                    ),
                tseServiceFactory =
                    TseServiceFactory(
                        tseTransactionRepository = noopProxy(),
                        tseSignClient = noopProxy(),
                        signer = TseVorgangSigner(noopProxy(), noopProxy(), noopProxy()),
                    ),
                tseSigningQueueService = noopProxy(),
                terminalRepository = noopProxy(),
                documents = testKassenbuchDocuments(pdfGenerationService = noopProxy(), documentManagementService = noopProxy()),
                favouriteProductService = noopProxy(),
                configManager = simpleConfigManager(),
                cardTerminalService = noopProxy(),
                cardSettlementService = noopProxy(),
                tableSessionRepository = noopProxy(),
                invoices = SalesInvoiceBillInvoiceLink(),
            )
    }

    @AfterEach
    fun teardown() {
        TestDatabase.teardown()
    }

    @Test
    fun `returns reports ordered by descending id`() =
        runBlocking {
            val older = createZBon(zbonNummer = 1L, totalPosRevenue = BigDecimal("100.00"))
            val newer = createZBon(zbonNummer = 2L, totalPosRevenue = BigDecimal("200.00"))
            val newest = createZBon(zbonNummer = 3L, totalPosRevenue = BigDecimal("300.00"))

            val reports = service.getRecentReports(limit = 2)

            assertEquals(2, reports.size)
            assertEquals(newest.id.value, reports[0].id)
            assertEquals(newer.id.value, reports[1].id)
        }

    @Test
    fun `returns all reports when count is below limit`() =
        runBlocking {
            createZBon(zbonNummer = 1L, totalPosRevenue = BigDecimal("50.00"))

            val reports = service.getRecentReports(limit = 5)

            assertEquals(1, reports.size)
            assertEquals(1L, reports[0].zbonNummer)
            assertEquals("50.00", reports[0].totalRevenue)
        }

    @Test
    fun `returns empty list when no reports exist`() =
        runBlocking {
            val reports = service.getRecentReports(limit = 5)

            assertEquals(0, reports.size)
        }

    @Test
    fun `maps summary fields correctly`() =
        runBlocking {
            // atStoredPrecision: the column holds microseconds, the Linux clock offers nanoseconds.
            val now = currentTimeStamp().atStoredPrecision()
            val report =
                createZBon(
                    zbonNummer = 42L,
                    totalPosRevenue = BigDecimal("123.45"),
                    startTime = now,
                    endTime = now,
                )

            val reports = service.getRecentReports(limit = 1)

            assertEquals(1, reports.size)
            val summary = reports[0]
            assertEquals(report.id.value, summary.id)
            assertEquals(42L, summary.zbonNummer)
            assertEquals("123.45", summary.totalRevenue)
            assertEquals(report.startTime.toString(), summary.startTime)
            assertEquals(report.endTime.toString(), summary.endTime)
            // Resolved server-side from ConfigKey.DEFAULT_CURRENCY (IShopConfigurationManager),
            // not a client-side constant — see ZBonServiceImpl.getRecentReports.
            assertEquals("EUR", summary.currency)
        }

    private fun createZBon(
        zbonNummer: Long,
        totalPosRevenue: BigDecimal,
        startTime: LocalDateTime = currentTimeStamp(),
        endTime: LocalDateTime = currentTimeStamp(),
    ): ZBon =
        TestData
            .createZBon(
                shop = shop,
                employee = employee,
                start = startTime,
                end = endTime,
                nummer = zbonNummer,
            ).also { transaction { it.totalPosRevenue = totalPosRevenue } }

    private inline fun <reified T : Any> noopProxy(): T {
        @Suppress("UNCHECKED_CAST")
        return Proxy.newProxyInstance(
            T::class.java.classLoader,
            arrayOf(T::class.java),
        ) { _, method, _ ->
            throw NotImplementedError("Stub: ${T::class.simpleName}.${method.name} should not be called in this test")
        } as T
    }

    private fun simpleConfigManager(): IShopConfigurationManager {
        @Suppress("UNCHECKED_CAST")
        return Proxy.newProxyInstance(
            IShopConfigurationManager::class.java.classLoader,
            arrayOf(IShopConfigurationManager::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getDefaultCurrency" -> "EUR"
                else -> throw NotImplementedError(
                    "Stub: IShopConfigurationManager.${method.name} should not be called in this test",
                )
            }
        } as IShopConfigurationManager
    }
}

package org.example.project.infrastructure.di

import org.example.project.features.bill.data.service.MapperBillSummaries
import org.example.project.features.bill.domain.service.BillInvoiceLink
import org.example.project.features.bill.domain.service.BillService
import org.example.project.features.bill.domain.service.BillSummaries
import org.example.project.features.bill.domain.service.SaleSigningGateway
import org.example.project.features.document.domain.service.PdfGenerationService
import org.example.project.features.order.domain.service.OrderItemService
import org.example.project.features.order.domain.service.OrderService
import org.example.project.features.printer.domain.service.PrinterService
import org.example.project.features.printer.domain.service.ReceiptPdfRenderer
import org.example.project.features.salesinvoice.data.service.SalesInvoiceBillInvoiceLink
import org.example.project.features.statistics.domain.repository.StatisticsRepository
import org.example.project.features.table.domain.service.TableSessionService
import org.example.project.features.tse.data.service.TseSaleSigningGateway
import org.example.project.features.tse.domain.provider.TseSignProvider
import org.example.project.printer.core.formatter.ZBonFormatter
import org.example.project.testutils.TestDatabase
import org.example.project.testutils.injectedValue
import org.example.project.testutils.testApp
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.Koin
import org.koin.core.context.GlobalContext
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.lang.reflect.Proxy
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame

/**
 * Ports the ecr area reads host wiring through must be bound by the production modules, and
 * injected with the very instances Koin holds (resolution alone would also pass for a second,
 * separately constructed implementation).
 */
@DisplayName("Ecr ports are bound by the production Koin modules")
class EcrPortsBindingTest {
    private lateinit var koin: Koin

    @BeforeEach
    fun start() {
        TestDatabase.setup()
        koin = koinApplication(createEagerInstances = false) { modules(serializationModule, httpClientModule, reposModule, servicesModule) }.koin
    }

    @AfterEach
    fun stop() {
        koin.close()
        TestDatabase.teardown()
    }

    @Test
    fun `printer service renders receipt PDFs through the PdfGenerationService singleton`() {
        val injected = injectedValue(koin.get<PrinterService>(), "pdfGenerationService")
        assertNotNull(injected)
        assertSame(koin.get<PdfGenerationService>(), injected)
    }

    @Test
    fun `the ReceiptPdfRenderer port resolves to the same PdfGenerationService singleton`() {
        assertSame(koin.get<PdfGenerationService>(), koin.get<ReceiptPdfRenderer>())
    }

    @Test
    fun `ZBonFormatter is resolvable`() {
        // ZBonRoutes injects it to hand PrinterService.printZBon the finished bytes; the printer
        // engine no longer binds it, so the host module must.
        assertSame(koin.get<ZBonFormatter>(), koin.get<ZBonFormatter>())
    }

    /**
     * The production `TseSignProvider` factory reads the global Koin context, which this isolated
     * application does not start; it is replaced by an inert stand-in so the test checks which
     * invoice link the bill service receives, not the device wiring.
     */
    @Test
    fun `bill service uses the accounting invoice link`() {
        val withDevice = koinWithInertDevice()
        try {
            val injected = injectedValue(withDevice.get<BillService>(), "invoices")
            assertNotNull(injected)
            assertIs<SalesInvoiceBillInvoiceLink>(injected)
            assertSame(withDevice.get<BillInvoiceLink>(), injected)
        } finally {
            withDevice.close()
        }
    }

    /**
     * The table-session service, the other consumer of the [BillInvoiceLink] port besides the bill
     * service, must hold the Koin singleton, not a separately constructed link.
     */
    @Test
    fun `table sessions use the accounting invoice link`() {
        val withDevice = koinWithInertDevice()
        try {
            val link = withDevice.get<BillInvoiceLink>()
            assertIs<SalesInvoiceBillInvoiceLink>(link)
            val injected = injectedValue(withDevice.get<TableSessionService>(), "invoices")
            assertNotNull(injected, "TableSessionService has no invoices")
            assertSame(link, injected, "TableSessionService must use the Koin BillInvoiceLink singleton")
        } finally {
            withDevice.close()
        }
    }

    /**
     * Statistics builds its bill and session summaries through the [BillSummaries] port (production
     * graph): the repository holds the Koin singleton, which is the ecr mapper implementation over the
     * Koin [BillInvoiceLink] singleton.
     */
    @Test
    fun `statistics builds summaries through the BillSummaries singleton`() {
        val withDevice = koinWithInertDevice()
        try {
            val summaries = withDevice.get<BillSummaries>()
            assertIs<MapperBillSummaries>(summaries)
            assertSame(withDevice.get<BillInvoiceLink>(), injectedValue(summaries, "invoices"))
            val injected = injectedValue(withDevice.get<StatisticsRepository>(), "billSummaries")
            assertNotNull(injected, "StatisticsRepository has no billSummaries")
            assertSame(summaries, injected)
        } finally {
            withDevice.close()
        }
    }

    /** The same binding in the `testApp` graph the route and service tests run against. */
    @Test
    fun `the testApp graph binds BillSummaries for statistics too`() =
        testApp { _ ->
            val koin = GlobalContext.get()
            val summaries = koin.get<BillSummaries>()
            assertIs<MapperBillSummaries>(summaries)
            assertSame(summaries, injectedValue(koin.get<StatisticsRepository>(), "billSummaries"))
        }

    /**
     * The three sale-signing services (spec §5 hard seam) must receive the fiscal gateway singleton,
     * not merely resolve: a second, separately constructed gateway would sign with its own factory.
     */
    @Test
    fun `order, order-item and bill services sign through the TSE sale-signing gateway`() {
        val withDevice = koinWithInertDevice()
        try {
            val gateway = withDevice.get<SaleSigningGateway>()
            assertIs<TseSaleSigningGateway>(gateway)
            listOf(withDevice.get<OrderService>(), withDevice.get<OrderItemService>(), withDevice.get<BillService>()).forEach { service ->
                val injected = injectedValue(service, "saleSigning")
                assertNotNull(injected, "${service::class.simpleName} has no saleSigning")
                assertSame(gateway, injected, "${service::class.simpleName} must use the Koin gateway singleton")
            }
        } finally {
            withDevice.close()
        }
    }

    private fun koinWithInertDevice(): Koin {
        val inertDevice =
            Proxy.newProxyInstance(TseSignProvider::class.java.classLoader, arrayOf(TseSignProvider::class.java)) { _, method, _ ->
                when (method.name) {
                    "toString" -> "InertTseSignProvider"
                    "hashCode" -> 0
                    "equals" -> false
                    else -> throw UnsupportedOperationException("TseSignProvider.${method.name} is not used here")
                }
            } as TseSignProvider
        return koinApplication(createEagerInstances = false) {
            allowOverride(true)
            modules(serializationModule, httpClientModule, reposModule, servicesModule, module { single<TseSignProvider> { inertDevice } })
        }.koin
    }
}

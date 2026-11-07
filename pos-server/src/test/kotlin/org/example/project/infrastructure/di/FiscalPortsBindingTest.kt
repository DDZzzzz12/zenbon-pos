package org.example.project.infrastructure.di

import org.example.project.features.bill.domain.service.BillInvoiceLink
import org.example.project.features.bill.domain.service.SaleSigningGateway
import org.example.project.features.datevexportlog.data.service.MonthlyCloseDatevStatusImpl
import org.example.project.features.datevexportlog.domain.service.DatevExportLogService
import org.example.project.features.document.data.service.KassenbuchDocumentsImpl
import org.example.project.features.document.domain.service.DocumentManagementService
import org.example.project.features.document.domain.service.PdfGenerationService
import org.example.project.features.dsfinvk.domain.service.DsfinvkExportService
import org.example.project.features.kassenbuch.domain.repository.KassenbuchRepository
import org.example.project.features.kassenbuch.domain.service.CashBookCloseService
import org.example.project.features.kassenbuch.domain.service.CashBookTransferService
import org.example.project.features.kassenbuch.domain.service.DatevApExportService
import org.example.project.features.kassenbuch.domain.service.KassenbuchDocuments
import org.example.project.features.kassenbuch.domain.service.KassenbuchService
import org.example.project.features.monthlyclose.domain.service.MonthlyCloseDatevStatus
import org.example.project.features.monthlyclose.domain.service.MonthlyCloseFinancials
import org.example.project.features.monthlyclose.domain.service.MonthlyCloseService
import org.example.project.features.salesinvoice.data.service.SalesInvoiceBillInvoiceLink
import org.example.project.features.salesinvoice.data.service.SalesInvoiceServiceImpl
import org.example.project.features.salesinvoice.domain.service.SalesInvoiceService
import org.example.project.features.statistics.data.service.MonthlyCloseFinancialsImpl
import org.example.project.features.statistics.domain.service.StatisticsService
import org.example.project.features.tse.data.service.TseSaleSigningGateway
import org.example.project.features.tse.domain.provider.TseAdminProvider
import org.example.project.features.tse.domain.provider.TseSignProvider
import org.example.project.features.zBon.domain.service.ZBonService
import org.example.project.testutils.TestDatabase
import org.example.project.testutils.injectedValue
import org.example.project.testutils.testApp
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.Koin
import org.koin.core.context.GlobalContext
import org.koin.core.error.InstanceCreationException
import org.koin.core.error.NoDefinitionFoundException
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.lang.reflect.Proxy
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Ports the fiscal area reaches accounting through must be bound by the production modules, and
 * injected with the very instances Koin holds (resolution alone would also pass for a second,
 * separately constructed implementation). Model: [EcrPortsBindingTest]. Each port is checked in an
 * isolated application over the production modules and again in the global graph `testApp` starts.
 *
 * The production `TseSignProvider` factory reads the global Koin context, which this isolated
 * application does not start; it is replaced by an inert stand-in (the Z-Bon and Kassenbuch services
 * build their TSE factory from it), so the test checks the document wiring, not the device.
 */
@DisplayName("Fiscal ports are bound by the production Koin modules")
class FiscalPortsBindingTest {
    private lateinit var koin: Koin

    @BeforeEach
    fun start() {
        TestDatabase.setup()
        koin =
            koinApplication(createEagerInstances = false) {
                allowOverride(true)
                modules(serializationModule, httpClientModule, reposModule, servicesModule, module { single<TseSignProvider> { inertDevice() } })
            }.koin
    }

    @AfterEach
    fun stop() {
        koin.close()
        TestDatabase.teardown()
    }

    @Test
    fun `the KassenbuchDocuments port is the accounting implementation over the document singletons`() {
        val documents = koin.get<KassenbuchDocuments>()
        assertIs<KassenbuchDocumentsImpl>(documents)
        assertSame(koin.get<PdfGenerationService>(), injectedValue(documents, "pdfGenerationService"))
        assertSame(koin.get<DocumentManagementService>(), injectedValue(documents, "documentManagementService"))
    }

    /**
     * Every fiscal consumer — the Kassenbuch service, both cash-book services, the Z-Bon closing and
     * the Kassenbuch repository (its link guard) — must hold the Koin singleton.
     */
    @Test
    fun `Kassenbuch, cash-book, Z-Bon services and the Kassenbuch repository use the KassenbuchDocuments singleton`() {
        val documents = koin.get<KassenbuchDocuments>()
        listOf<Any>(
            koin.get<KassenbuchService>(),
            koin.get<CashBookCloseService>(),
            koin.get<CashBookTransferService>(),
            koin.get<ZBonService>(),
            koin.get<KassenbuchRepository>(),
        ).forEach { consumer ->
            val injected = injectedValue(consumer, "documents")
            assertNotNull(injected, "${consumer::class.simpleName} has no documents")
            assertSame(documents, injected, "${consumer::class.simpleName} must use the Koin KassenbuchDocuments singleton")
        }
    }

    /**
     * The clearing-bill original lookup (B2-3 A8): the DSFinV-K export (its header reference) and the
     * Z-Bon closing (its drawer derivation) read it through the ecr port, never the accounting model.
     * The export also takes the TSE admin provider, whose production factory reads the global Koin
     * context; it gets an inert stand-in like the signing device.
     */
    @Test
    fun `DSFinV-K export and Z-Bon closing use the BillInvoiceLink singleton`() {
        koin.loadModules(listOf(module { single<TseAdminProvider> { inert() } }), allowOverride = true)
        val link = koin.get<BillInvoiceLink>()
        assertIs<SalesInvoiceBillInvoiceLink>(link)
        listOf<Any>(koin.get<DsfinvkExportService>(), koin.get<ZBonService>()).forEach { consumer ->
            val injected = injectedValue(consumer, "invoices")
            assertNotNull(injected, "${consumer::class.simpleName} has no invoices")
            assertSame(link, injected, "${consumer::class.simpleName} must use the Koin BillInvoiceLink singleton")
        }
    }

    /**
     * B2-4 A3: the till clearing signs its clearing bill through the fiscal sale-signing port, never
     * through the TSE factory, and must hold the one fiscal implementation Koin binds.
     */
    @Test
    fun `the sales-invoice service signs through the SaleSigningGateway singleton`() {
        val gateway = koin.get<SaleSigningGateway>()
        assertIs<TseSaleSigningGateway>(gateway)
        val invoices = koin.get<SalesInvoiceService>()
        assertIs<SalesInvoiceServiceImpl>(invoices)
        assertSame(gateway, injectedValue(invoices, "saleSigning"))
    }

    /** The same wiring holds in the global graph every `testApp` test runs against (B2-3 A5 trap). */
    @Test
    fun `testApp provides the SaleSigningGateway to the sales-invoice service`() =
        testApp { _ ->
            val global = GlobalContext.get()
            val gateway = global.get<SaleSigningGateway>()
            assertIs<TseSaleSigningGateway>(gateway)
            assertSame(gateway, injectedValue(global.get<SalesInvoiceService>(), "saleSigning"))
        }

    /**
     * The other fiscal-to-accounting ports, checked the same way in the global graph `testApp` starts (the
     * production modules with real TSE providers, built from `GlobalContext`): every consumer above holds
     * the one Koin singleton of [KassenbuchDocuments], [BillInvoiceLink], [MonthlyCloseFinancials] and
     * [MonthlyCloseDatevStatus], each of which is the accounting/backoffice implementation.
     */
    @Test
    fun `testApp provides every fiscal port to its consumers`() =
        testApp { _ ->
            val global = GlobalContext.get()

            val documents = global.get<KassenbuchDocuments>()
            assertIs<KassenbuchDocumentsImpl>(documents)
            listOf<Any>(
                global.get<KassenbuchService>(),
                global.get<CashBookCloseService>(),
                global.get<CashBookTransferService>(),
                global.get<ZBonService>(),
                global.get<KassenbuchRepository>(),
            ).forEach { consumer ->
                assertSame(documents, injectedValue(consumer, "documents"), "${consumer::class.simpleName} documents")
            }

            val link = global.get<BillInvoiceLink>()
            assertIs<SalesInvoiceBillInvoiceLink>(link)
            listOf<Any>(global.get<DsfinvkExportService>(), global.get<ZBonService>()).forEach { consumer ->
                assertSame(link, injectedValue(consumer, "invoices"), "${consumer::class.simpleName} invoices")
            }

            val financials = global.get<MonthlyCloseFinancials>()
            assertIs<MonthlyCloseFinancialsImpl>(financials)
            val datev = global.get<MonthlyCloseDatevStatus>()
            assertIs<MonthlyCloseDatevStatusImpl>(datev)
            val monthlyClose = global.get<MonthlyCloseService>()
            assertSame(financials, injectedValue(monthlyClose, "financials"))
            assertSame(datev, injectedValue(monthlyClose, "datevStatus"))
        }

    @Test
    fun `the month-close financials port is the backoffice implementation over the statistics singleton`() {
        val financials = koin.get<MonthlyCloseFinancials>()
        assertIs<MonthlyCloseFinancialsImpl>(financials)
        assertSame(koin.get<StatisticsService>(), injectedValue(financials, "statisticsService"))
    }

    @Test
    fun `the month-close DATEV port is the accounting implementation over the export-log and AP-export singletons`() {
        val datev = koin.get<MonthlyCloseDatevStatus>()
        assertIs<MonthlyCloseDatevStatusImpl>(datev)
        assertSame(koin.get<DatevExportLogService>(), injectedValue(datev, "exportLogService"))
        assertSame(koin.get<DatevApExportService>(), injectedValue(datev, "apExportService"))
    }

    @Test
    fun `the month close uses both port singletons`() {
        val monthlyClose = koin.get<MonthlyCloseService>()
        assertSame(koin.get<MonthlyCloseFinancials>(), injectedValue(monthlyClose, "financials"))
        assertSame(koin.get<MonthlyCloseDatevStatus>(), injectedValue(monthlyClose, "datevStatus"))
    }

    /**
     * Review focus: a month close without a provider must fail when it is wired, never run and produce
     * an empty check. Removing a binding (loading a stand-in over it and unloading that again drops the
     * key) makes resolving the month close throw, naming the missing port.
     */
    @Test
    fun `the month close cannot be built when a month-close port has no implementation`() {
        val standIns =
            mapOf(
                MonthlyCloseFinancials::class to module { single<MonthlyCloseFinancials> { error("stand-in, never resolved") } },
                MonthlyCloseDatevStatus::class to module { single<MonthlyCloseDatevStatus> { error("stand-in, never resolved") } },
            )
        standIns.forEach { (port, standIn) ->
            val app =
                koinApplication(createEagerInstances = false) {
                    allowOverride(true)
                    modules(serializationModule, httpClientModule, reposModule, servicesModule, module { single<TseSignProvider> { inertDevice() } })
                }
            try {
                app.koin.loadModules(listOf(standIn), allowOverride = true)
                app.koin.unloadModules(listOf(standIn))
                assertFailsWith<NoDefinitionFoundException>("${port.simpleName} is unbound") { app.koin.get<Any>(port) }
                val failure = assertFailsWith<InstanceCreationException> { app.koin.get<MonthlyCloseService>() }
                val cause = generateSequence<Throwable>(failure) { it.cause }.firstOrNull { it is NoDefinitionFoundException }
                assertNotNull(cause, "the month close must fail on the missing ${port.simpleName}, got $failure")
                assertTrue(port.qualifiedName!! in cause.message.orEmpty(), cause.message)
            } finally {
                app.close()
            }
        }
    }

    private fun inertDevice(): TseSignProvider = inert()

    private inline fun <reified T : Any> inert(): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            when (method.name) {
                "toString" -> "Inert${T::class.simpleName}"
                "hashCode" -> 0
                "equals" -> false
                else -> throw UnsupportedOperationException("${T::class.simpleName}.${method.name} is not used here")
            }
        } as T
}

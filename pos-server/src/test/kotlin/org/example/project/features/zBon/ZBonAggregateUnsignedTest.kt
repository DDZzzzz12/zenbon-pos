package org.example.project.features.zBon

import kotlinx.coroutines.runBlocking
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.core.domain.dto.document.DocumentDto
import org.example.project.core.domain.dto.tse.TseConfigDto
import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.core.domain.enums.bill.PaymentStatus
import org.example.project.core.domain.enums.bill.TipRecipient
import org.example.project.core.domain.enums.kassenbuch.KassenbuchLineKind
import org.example.project.core.domain.enums.rTable.TableStatus
import org.example.project.core.domain.enums.salesinvoice.RevenueOrigin
import org.example.project.core.domain.enums.salesinvoice.SalesInvoiceStatus
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.domain.enums.tse.TseSigningStatus
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.bill.domain.models.BillVatDetail
import org.example.project.features.document.domain.models.Document
import org.example.project.features.document.domain.service.PdfGenerationService
import org.example.project.features.employee.data.repository.EmployeeRepositoryImpl
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.kassenbuch.data.repository.CashBookRepositoryImpl
import org.example.project.features.kassenbuch.data.repository.KassenbuchRepositoryImpl
import org.example.project.features.kassenbuch.data.service.CashBookTransferServiceImpl
import org.example.project.features.kassenbuch.data.service.KassenbuchTseSigner
import org.example.project.features.kassenbuch.database.tables.KassenbuchHeaderTable
import org.example.project.features.kassenbuch.domain.models.KassenbuchHeader
import org.example.project.features.payment.domain.models.Payment
import org.example.project.features.payment.domain.models.PaymentType
import org.example.project.features.salesinvoice.data.service.SalesInvoiceBillInvoiceLink
import org.example.project.features.salesinvoice.domain.models.SalesInvoice
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.table.data.repository.TableSessionRepositoryImpl
import org.example.project.features.table.domain.models.RTable
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.terminal.data.repository.TerminalRepositoryImpl
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.features.tse.data.repository.TseTransactionRepositoryImpl
import org.example.project.features.tse.data.service.TseVorgangSigner
import org.example.project.features.tse.domain.factory.TseServiceFactory
import org.example.project.features.tse.domain.provider.TseSignProvider
import org.example.project.features.zBon.data.repository.ZBonRepositoryImpl
import org.example.project.features.zBon.data.service.ZBonServiceImpl
import org.example.project.infrastructure.di.httpClientModule
import org.example.project.infrastructure.di.reposModule
import org.example.project.infrastructure.di.serializationModule
import org.example.project.infrastructure.di.servicesModule
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.example.project.testutils.testKassenbuchDocuments
import org.example.project.utils.NonTaxableTipCarrier
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import java.lang.reflect.Proxy
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The daily-close `CASH_SALE` aggregate is bookkeeping over sales each bill already signed as its
 * own Kassenbeleg, so it creates no TSE transaction (AEAO zu § 146a Nr. 1.11.2; spec
 * 2026-10-09-zbon-close-anywhere). The row stays for the running balance (Kassensturzfähigkeit) and
 * carries `NOT_APPLICABLE`. Cash movements of a closing (here the Kassendifferenz) still sign.
 *
 * Driven through the real `ZBonServiceImpl.createReport`, captured at [TseSignProvider.quickSign]
 * (the `testApp` harness runs TSE DISABLED, where nothing reaches a provider).
 */
@DisplayName("Z-Bon aggregate is unsigned; closing movements still sign")
class ZBonAggregateUnsignedTest {
    private val DEV = "test-device"

    private lateinit var service: ZBonServiceImpl
    private lateinit var admin: Employee
    private lateinit var shop: ShopInfo

    /** Every (processType, processData, clientId) handed to the TSE, in signing order. */
    private val signed = mutableListOf<Triple<String, String, String>>()

    /** The fixture's cash bill, so a test can tip it before the closing runs. */
    private var cashBillId = 0L

    /** The receivable tenders [addTwoCashClearedReceivables] opens, read back by its assertions. */
    private var tenderAId = 0L
    private var tenderBId = 0L

    @Test
    fun `a plain closing signs nothing and stores the aggregate NOT_APPLICABLE`() {
        runBlocking { service.createReport(admin.id.value.toString(), DEV) }

        assertTrue(signed.isEmpty(), "no TSE transaction for a closing without cash movements: $signed")
        transaction {
            val aggregate = KassenbuchHeader.find { KassenbuchHeaderTable.isZbonAggregate eq true }.single()
            assertEquals(TseSigningStatus.NOT_APPLICABLE, aggregate.tseSigningStatus)
            assertNull(aggregate.tseTransaction)
        }
    }

    @Test
    fun `the aggregate's lines still sum to its header`() {
        runBlocking { service.createReport(admin.id.value.toString(), DEV) }
        transaction {
            val aggregate = KassenbuchHeader.find { KassenbuchHeaderTable.isZbonAggregate eq true }.single()
            assertEquals(0, aggregate.lineItems.sumOf { it.vorzBetrag }.compareTo(aggregate.totalAmount))
            assertEquals(0, BigDecimal("119.00").compareTo(aggregate.totalAmount))
        }
    }

    @Test
    fun `a counted closing signs exactly the Kassendifferenz, under the calling till`() {
        runBlocking { service.createReport(admin.id.value.toString(), DEV, countedAmount = "118.00") }
        assertEquals(listOf("client-1"), signed.map { it.third }, "one signature, the difference, by the till: $signed")
    }

    /**
     * The same day with a 6.00 TrinkgeldAN on the cash bill. The aggregate is no longer signed, so
     * only the line model is pinned: the tip is its own `TE:Trinkgeld (AN)` EMPLOYEE_TIP line at
     * rate "0" and carries the whole tip.
     */
    @Test
    fun `a tipped aggregate carries the employee tip as an EMPLOYEE_TIP line at rate 0`() {
        tipTheCashBillForAnEmployee()

        runBlocking { service.createReport(admin.id.value.toString(), DEV) }

        assertTrue(signed.isEmpty(), "the aggregate is not signed, tipped or not: $signed")
        transaction {
            val entry = KassenbuchHeader.find { KassenbuchHeaderTable.isZbonAggregate eq true }.single()
            val tipLine = entry.lineItems.single { it.belegtext == "TE:Trinkgeld (AN)" }
            assertEquals(KassenbuchLineKind.EMPLOYEE_TIP, tipLine.kind, "the Z-Bon tip line is an EMPLOYEE_TIP line")
            assertEquals("0", tipLine.ustSatz, "the tip line is rated \"0\"")
            assertEquals(0, BigDecimal("6.00").compareTo(tipLine.vorzBetrag), "the tip line carries the whole tip")
        }
    }

    /**
     * Two receivables opened under DIFFERENT tenders and cleared in cash on one day: the aggregate
     * carries ONE settlement line per tender, each naming the tender that OPENED its receivable (the
     * export resolves the contra account from it), rate-less, and the lines still sum to the header.
     */
    @Test
    fun `two cash-cleared receivables split per tender`() {
        addTwoCashClearedReceivables()

        runBlocking { service.createReport(admin.id.value.toString(), DEV) }

        assertTrue(signed.isEmpty(), "the aggregate is not signed: $signed")

        transaction {
            val entry = KassenbuchHeader.find { KassenbuchHeaderTable.isZbonAggregate eq true }.single()
            val lines = entry.lineItems.sortedBy { it.lineNumber }
            val settlements = lines.filter { it.kind == KassenbuchLineKind.RECEIVABLE_SETTLEMENT }

            assertEquals(
                2,
                settlements.size,
                "one settlement line per receivable tender; got ${lines.map { it.belegtext to it.vorzBetrag }}",
            )
            assertTrue(
                settlements.all { it.ustSatz == null },
                "a Forderungsausgleich is rate-less BY DESIGN — a rate would re-book revenue the " +
                    "invoice already booked AND change the exported DSFinV-K bytes; got ${settlements.map { it.ustSatz }}",
            )
            assertEquals(
                setOf(tenderAId, tenderBId),
                settlements.mapNotNull { it.paymentType?.id?.value }.toSet(),
                "each line must name the tender that OPENED its receivable, or the export cannot " +
                    "resolve its contra account and refuses the whole period",
            )
            assertEquals(
                0,
                lines.sumOf { it.vorzBetrag }.compareTo(entry.totalAmount),
                "the aggregate's lines must still sum to its header (${entry.totalAmount}); got " +
                    "${lines.map { it.belegtext to it.vorzBetrag }}",
            )
        }
    }

    /**
     * Opens two receivables under two different `createsReceivable` tenders and clears both in cash,
     * on top of the base fixture's cash and card sales.
     *
     * Each original is an ordinary 19 % sale settled auf Rechnung, so it contributes turnover AND a
     * `NON_CASH_PAYMENT` opening leg — the very leg whose `mapping.paymentTypes` cell the clearing
     * must later clear. Both bills are left unattributed so this closing sweeps them.
     *
     * The clearings use their own cash tender rather than the fixture's: cash identity is irrelevant
     * to the split (clearings group by the OPENING tender), so a separate one keeps this helper
     * self-contained.
     */
    private fun addTwoCashClearedReceivables() {
        val tenderA = TestData.createPaymentType(name = "Auf Rechnung A", isCash = false)
        val tenderB = TestData.createPaymentType(name = "Auf Rechnung B", isCash = false)
        val clearingCash = TestData.createPaymentType(name = "Bar Ausgleich", isCash = true)
        val vat19 = TestData.createDefaultVatRates().first()
        tenderAId = tenderA.id.value
        tenderBId = tenderB.id.value

        transaction {
            PaymentType.findById(tenderA.id)!!.createsReceivable = true
            PaymentType.findById(tenderB.id)!!.createsReceivable = true

            val session = TableSession.all().single()
            val now = currentTimeStamp()

            fun openAndClear(
                tender: PaymentType,
                gross: String,
                net: String,
                vatAmount: String,
                tag: String,
            ) {
                val amount = BigDecimal(gross)
                val original =
                    Bill.new {
                        this.employeeId = admin
                        this.tableSession = session
                        this.billNumber = "ZBAGG-OPEN-$tag"
                        this.billAmount = amount
                        this.paidAmount = amount
                        this.tips = BigDecimal.ZERO
                        this.startTime = now
                        this.endTime = now
                    }
                BillVatDetail.new {
                    this.bill = original
                    this.vat = vat19
                    this.netAmount = BigDecimal(net)
                    this.vatAmount = BigDecimal(vatAmount)
                    this.totalAmount = amount
                }
                Payment.new {
                    this.bill = original
                    this.paymentType = tender
                    this.amount = amount
                    this.currency = "EUR"
                    this.paymentStatus = PaymentStatus.COMPLETED
                    this.paymentTime = now
                    // The leg that makes this a receivable — what the derivation reads back.
                    this.createsReceivable = true
                }

                // `shop` is captured into a local FIRST: inside `SalesInvoice.new { }` the receiver
                // has its own `shop` property (`var shop by ShopInfo referencedOn …`), which shadows
                // the test's field — so `this.shop = shop` read the entity's own unset reference and
                // Exposed threw "null cannot be cast to non-null type REF". The passing fixtures in
                // ZBonReceivableClearingTest and DsfinvkClearingModelTest avoid this only because
                // they happen to hold `shop` in a local.
                val shopInfo = shop
                val invoice =
                    SalesInvoice.new {
                        this.shop = shopInfo
                        this.revenueOrigin = RevenueOrigin.POS_RECEIVABLE
                        this.status = SalesInvoiceStatus.ISSUED
                        this.buyerName = "Catering $tag GmbH"
                        this.issueDate = now
                        this.serviceDate = now
                        this.billId = original.id
                    }

                val clearing =
                    Bill.new {
                        this.employeeId = admin
                        this.tableSession = session
                        this.billNumber = "ZBAGG-CLR-$tag"
                        this.billAmount = amount
                        this.paidAmount = amount
                        this.tips = BigDecimal.ZERO
                        this.startTime = now
                        this.endTime = now
                        this.clearsSalesInvoiceId = invoice.id.value
                    }
                Payment.new {
                    this.bill = clearing
                    this.paymentType = clearingCash
                    this.amount = amount
                    this.currency = "EUR"
                    this.paymentStatus = PaymentStatus.COMPLETED
                    this.paymentTime = now
                    this.createsReceivable = false
                }
            }

            openAndClear(tenderA, gross = "50.00", net = "42.02", vatAmount = "7.98", tag = "A")
            openAndClear(tenderB, gross = "30.00", net = "25.21", vatAmount = "4.79", tag = "B")
        }
    }

    /**
     * Turns the fixture's cash bill into a post-P1.1 EMPLOYEE-tipped one: 6.00 of tip on top of
     * 119.00 of taxable turnover, all 125.00 tendered in cash, with NO 0 % VAT row — the shape
     * `BillServiceImpl` has produced since P1.1. `ZBonEmployeeTipLineTest` is the witness that the
     * real `createBill` produces it; this class hand-seeds because it captures the signed payload
     * through the service's own TSE provider.
     *
     * The bill moves to a MANAGER because `BillRepositoryImpl` derives TrinkgeldAN for a non-ADMIN
     * closer and TrinkgeldAG for an ADMIN: an admin-closed TrinkgeldAN bill is not a shape production
     * can reach, and it would also divert the tip into `report.adminTip`.
     */
    private fun tipTheCashBillForAnEmployee() {
        val manager = TestData.createManager(employeeNumber = "ZBAGG_MGR", username = "zbagg_mgr")
        transaction {
            val bill = Bill.findById(cashBillId)!!
            bill.employeeId = Employee.findById(manager.id)!!
            bill.tipRecipient = TipRecipient.EMPLOYEE
            bill.tips = BigDecimal("6.00")
            bill.paidAmount = BigDecimal("125.00")
            bill.payments.single().amount = BigDecimal("125.00")

            // Fixture guard: without it the test could pass vacuously through the carrier's
            // pre-upgrade branch, which returns ZERO when the VAT rows already sum to paidAmount.
            assertEquals(
                0,
                BigDecimal("6.00").compareTo(NonTaxableTipCarrier.outsideVatDetails(bill)),
                "the tip must sit OUTSIDE the VAT breakdown, or this is a pre-P1.1 bill and the " +
                    "0-rate line under test is never built",
            )
        }
    }

    // ---------------------------------------------------------------- fixtures

    @BeforeEach
    fun setup() {
        TestDatabase.setup()
        GlobalContext.getOrNull()?.let { GlobalContext.stopKoin() }
        startKoin {
            modules(serializationModule, httpClientModule, reposModule, servicesModule)
        }
        signed.clear()

        shop = TestData.createShopInfo()
        admin = TestData.createAdmin()
        val vats = TestData.createDefaultVatRates()
        val cash = TestData.createPaymentType(name = "Cash", isCash = true)
        val card = TestData.createPaymentType(name = "Card", isCash = false)
        val tws = TestData.createTableWithSession("ZBAGG")

        // TseServiceFactory reads the GLOBAL ShopConfigurationManager singleton, so the real signing
        // path is only reachable by mutating the singleton. Reset in @AfterEach.
        runBlocking {
            ShopConfigurationManager.setTseConfig(
                TseConfigDto.TseConfig(mode = TseMode.NORMAL, timeAdminPin = "12345"),
            )
        }

        service =
            ZBonServiceImpl(
                repository = ZBonRepositoryImpl(),
                kassenbuchRepository = KassenbuchRepositoryImpl(testKassenbuchDocuments()),
                cashBookRepository = CashBookRepositoryImpl(),
                cashBookTransferService =
                    CashBookTransferServiceImpl(
                        configManager = ShopConfigurationManager,
                        cashBookRepository = CashBookRepositoryImpl(),
                        kassenbuchRepository = KassenbuchRepositoryImpl(testKassenbuchDocuments()),
                        employeeRepository = EmployeeRepositoryImpl(),
                        terminalRepository = TerminalRepositoryImpl(),
                        documents = testKassenbuchDocuments(pdfGenerationService = pdfStub()),
                        tseSigner =
                            KassenbuchTseSigner(
                                configManager = ShopConfigurationManager,
                                tseServiceFactory =
                                    TseServiceFactory(
                                        tseTransactionRepository = TseTransactionRepositoryImpl(),
                                        tseSignClient = bestEffortProxy(),
                                        signer = TseVorgangSigner(bestEffortProxy(), TseTransactionRepositoryImpl(), bestEffortProxy()),
                                    ),
                                tseSigningQueueService = bestEffortProxy(),
                            ),
                    ),
                tseServiceFactory =
                    TseServiceFactory(
                        tseTransactionRepository = TseTransactionRepositoryImpl(),
                        tseSignClient = capturingSignProvider,
                        signer = TseVorgangSigner(capturingSignProvider, TseTransactionRepositoryImpl(), bestEffortProxy()),
                    ),
                tseSigningQueueService = bestEffortProxy(),
                terminalRepository = TerminalRepositoryImpl(),
                documents = testKassenbuchDocuments(pdfGenerationService = pdfStub(), documentManagementService = bestEffortProxy()),
                favouriteProductService = bestEffortProxy(),
                configManager = ShopConfigurationManager,
                cardTerminalService = bestEffortProxy(),
                cardSettlementService = bestEffortProxy(),
                tableSessionRepository = TableSessionRepositoryImpl(),
                invoices = SalesInvoiceBillInvoiceLink(),
            )

        transaction {
            // H2 stand-in for the Postgres advisory lock `createReport` takes before anything else.
            exec(
                "CREATE ALIAS IF NOT EXISTS PG_ADVISORY_XACT_LOCK AS " +
                    "'long pgAdvisoryXactLock(long key) { return key; }'",
            )
            val now = currentTimeStamp()

            Terminal.new {
                this.deviceId = DEV
                this.clientId = "client-1"
                this.tseClientRegistered = true
                this.isActive = true
                this.registeredAt = now
                this.lastSeenAt = now
            }

            // Close the session and free the table so the Z-Bon session/table gate passes.
            val session = TableSession.findById(tws.session.id)!!
            val table = RTable.findById(tws.table.id)!!
            session.completed = true
            session.endTime = now
            table.currentSession = null
            table.tableStatus = TableStatus.FREE

            // One cash bill and one card bill: revenue in two VAT groups plus a NON_CASH_PAYMENT
            // tender line, i.e. the exact aggregate shape the Kassenbeleg-V1 branch used to bucket.
            fun newBill(
                nr: String,
                amount: String,
            ): Bill =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = session
                    this.billNumber = nr
                    this.billAmount = BigDecimal(amount)
                    this.paidAmount = BigDecimal(amount)
                    this.tips = BigDecimal.ZERO
                    this.startTime = now
                    this.endTime = now
                }

            val cashBill = newBill("ZBAGG-1", "119.00")
            val cardBill = newBill("ZBAGG-2", "107.00")
            cashBillId = cashBill.id.value

            BillVatDetail.new {
                this.bill = cashBill
                this.vat = vats[0]
                this.netAmount = BigDecimal("100.00")
                this.vatAmount = BigDecimal("19.00")
                this.totalAmount = BigDecimal("119.00")
            }
            BillVatDetail.new {
                this.bill = cardBill
                this.vat = vats[1]
                this.netAmount = BigDecimal("100.00")
                this.vatAmount = BigDecimal("7.00")
                this.totalAmount = BigDecimal("107.00")
            }

            fun pay(
                target: Bill,
                type: org.example.project.features.payment.domain.models.PaymentType,
                value: String,
            ) = Payment.new {
                this.bill = target
                this.paymentType = type
                this.amount = BigDecimal(value)
                this.currency = "EUR"
                this.paymentStatus = PaymentStatus.COMPLETED
                this.paymentTime = now
            }
            pay(cashBill, cash, "119.00")
            pay(cardBill, card, "107.00")
        }
    }

    @AfterEach
    fun teardown() {
        runBlocking {
            ShopConfigurationManager.setTseConfig(
                TseConfigDto.TseConfig(mode = TseMode.DISABLED, timeAdminPin = "12345"),
            )
        }
        GlobalContext.getOrNull()?.let { GlobalContext.stopKoin() }
        TestDatabase.teardown()
    }

    private val capturingSignProvider =
        object : TseSignProvider {
            private var txCounter = 0L

            override suspend fun quickSign(
                processType: String,
                processData: String,
                clientId: String,
            ): TseDto.SignResultDto {
                signed += Triple(processType, processData, clientId)
                txCounter += 1
                return TseDto.SignResultDto(
                    success = true,
                    processType = processType,
                    transactionNumber = txCounter,
                    signature = "SIG-$txCounter",
                    timestamp = 1_752_000_000L,
                    serialNumber = "AA".repeat(32),
                    signatureCounter = txCounter,
                    signatureAlgorithm = "ecdsa-plain-SHA384",
                    startTimestamp = 1_752_000_000L,
                )
            }

            override suspend fun startTransaction(
                processType: String,
                clientId: String,
            ) = throw NotImplementedError("the Z-Bon aggregate signs via quickSign only")

            override suspend fun updateTransaction(
                transactionNumber: Long,
                processData: String,
                clientId: String,
            ) = throw NotImplementedError("the Z-Bon aggregate signs via quickSign only")

            override suspend fun finishTransaction(
                transactionNumber: Long,
                processData: String,
                clientId: String,
                processType: String,
            ) = throw NotImplementedError("the Z-Bon aggregate signs via quickSign only")

            override suspend fun checkHealth(): TseDto.HealthCheckResultDto =
                TseDto.HealthCheckResultDto(
                    healthy = true,
                    initialized = true,
                    validTime = true,
                    passedSelfTest = true,
                    remainingSignatures = 1_000L,
                    availableCapacity = 1_000L,
                )

            override fun isConnected(): Boolean = true

            override fun getCachedMode(): String = "SIMULATION"

            override fun registerConnectionListener() = Unit

            override suspend fun restartConnection(): TseDto.HealthCheckResultDto? = null

            override fun close() = Unit
        }

    private var docCounter = 0

    /**
     * Like [bestEffortProxy] for the PDF service, but `generateEigenbelegPdf` — which the closing's
     * Kassendifferenz needs — persists a Document row and returns it. Every other call still throws an
     * `Exception` that the closing's best-effort steps swallow.
     */
    private fun pdfStub(): PdfGenerationService =
        Proxy.newProxyInstance(
            PdfGenerationService::class.java.classLoader,
            arrayOf(PdfGenerationService::class.java),
        ) { _, method, _ ->
            if (method.name != "generateEigenbelegPdf") {
                throw UnsupportedOperationException("Stub: PdfGenerationService.${method.name} is best-effort here")
            }
            val n = ++docCounter
            val id =
                transaction {
                    Document
                        .new {
                            this.filename = "eigenbeleg-$n.pdf"
                            this.fileType = "INVOICE"
                            this.filePath = "/tmp/test/zbon-$n.pdf"
                            this.fileSize = 1024L
                            this.uploadedAt = currentTimeStamp()
                            this.uploadedBy = admin
                            this.isActive = true
                            this.contentHash = "hash-zbon-$n"
                            this.mediaType = "application/pdf"
                        }.id.value
                }
            DocumentDto.DocumentResponseDto(
                id = id,
                filename = "eigenbeleg-$n.pdf",
                fileType = "INVOICE",
                fileSize = 1024L,
                uploadedAt = "2026-07-18T00:00:00",
                uploadedBy = admin.id.value.toString(),
                downloadUrl = "/download/$id",
                formattedFileSize = "1.0 KB",
                monthFolder = "2026-07",
            )
        } as PdfGenerationService

    /**
     * Throws an [UnsupportedOperationException] — an `Exception`, NOT the `NotImplementedError` the
     * sibling suites use. `createReport`'s PDF, favourites and ZVT-settlement steps are best-effort
     * and swallow `Exception`; an `Error` would escape those `catch` blocks and fail the closing for
     * a collaborator this test does not care about.
     */
    private inline fun <reified T : Any> bestEffortProxy(): T {
        @Suppress("UNCHECKED_CAST")
        return Proxy.newProxyInstance(
            T::class.java.classLoader,
            arrayOf(T::class.java),
        ) { _, method, _ ->
            throw UnsupportedOperationException("Stub: ${T::class.simpleName}.${method.name} is best-effort here")
        } as T
    }
}

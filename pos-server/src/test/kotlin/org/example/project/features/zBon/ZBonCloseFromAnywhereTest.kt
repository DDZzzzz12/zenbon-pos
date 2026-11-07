package org.example.project.features.zBon

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.core.domain.dto.document.DocumentDto
import org.example.project.core.domain.dto.tse.TseConfigDto
import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.core.domain.enums.bill.PaymentStatus
import org.example.project.core.domain.enums.kassenbuch.CashBookCloseInterval
import org.example.project.core.domain.enums.rTable.TableStatus
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.domain.enums.terminal.TerminalRole
import org.example.project.core.domain.enums.zBon.ClosingSignerProblem
import org.example.project.core.error.PosErrorCode
import org.example.project.features.audit.database.tables.TransactionAuditLogTable
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
import org.example.project.features.kassenbuch.domain.models.KassenbuchHeader
import org.example.project.features.payment.domain.models.Payment
import org.example.project.features.payment.domain.models.PaymentType
import org.example.project.features.salesinvoice.data.service.SalesInvoiceBillInvoiceLink
import org.example.project.features.setup.database.tables.ConfigKey
import org.example.project.features.setup.database.tables.ShopConfigurationTable
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.table.data.repository.TableSessionRepositoryImpl
import org.example.project.features.table.domain.models.RTable
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.terminal.data.repository.TerminalRepositoryImpl
import org.example.project.features.terminal.database.tables.TerminalTable
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.features.tse.data.repository.TseTransactionRepositoryImpl
import org.example.project.features.tse.data.service.TseVorgangSigner
import org.example.project.features.tse.domain.factory.TseServiceFactory
import org.example.project.features.tse.domain.provider.TseSignProvider
import org.example.project.features.zBon.data.repository.ZBonRepositoryImpl
import org.example.project.features.zBon.data.service.ZBonServiceImpl
import org.example.project.features.zBon.domain.ClosingSigner
import org.example.project.features.zBon.domain.models.ZBon
import org.example.project.infrastructure.di.httpClientModule
import org.example.project.infrastructure.di.reposModule
import org.example.project.infrastructure.di.serializationModule
import org.example.project.infrastructure.di.servicesModule
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.example.project.testutils.testKassenbuchDocuments
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import java.lang.reflect.Proxy
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Any principal may close from the adminApp (a device that is not a till): a plain day needs no
 * signer; a closing with cash movements is signed by the till designated on the Ladenkasse, else the
 * calling till, else refused 409 before anything is written (spec 2026-10-09-zbon-close-anywhere).
 */
@DisplayName("Z-Bon close from anywhere: designated signer")
class ZBonCloseFromAnywhereTest {
    private val DEV = "test-device"
    private val ADMIN_WEB = "admin-web-4f1c"

    private lateinit var service: ZBonServiceImpl
    private lateinit var admin: Employee
    private lateinit var shop: ShopInfo

    /** Every (processType, processData, clientId) handed to the TSE, in signing order. */
    private val signed = mutableListOf<Triple<String, String, String>>()

    private fun addSecondTill() {
        transaction {
            val now = currentTimeStamp()
            Terminal.new {
                deviceId = "dev-2"
                clientId = "TER-002"
                name = "Kasse 2"
                isActive = true
                fiscal = true
                tseClientRegistered = true
                role = TerminalRole.CASHIER.name
                shopInfo = shop
                registeredAt = now
                lastSeenAt = now
            }
        }
    }

    private fun designate(clientId: String?) =
        transaction {
            CashBookRepositoryImpl().fiscalBookFor(shop).signingTerminal =
                clientId?.let { c -> Terminal.find { TerminalTable.clientId eq c }.single() }
        }

    private fun configureDailyTransfer() {
        val tresorId =
            transaction {
                CashBookRepositoryImpl()
                    .create(shop = shop, name = "Tresor", isFiscal = false, datevAccount = "1010", closeInterval = CashBookCloseInterval.MANUAL)
                    .id.value
            }
        transaction {
            ShopConfigurationTable.upsert {
                it[ShopConfigurationTable.shopId] = shop.id.value
                it[ShopConfigurationTable.key] = ConfigKey.CASH_BOOK_TRANSFER_TARGET.key
                it[ShopConfigurationTable.value] = JsonPrimitive(tresorId.toString())
                it[ShopConfigurationTable.description] = "test"
                it[ShopConfigurationTable.updatedAt] = null
            }
        }
        runBlocking { ShopConfigurationManager.refreshConfiguration() }
    }

    @Test
    fun `the adminApp closes a plain day with nothing designated`() {
        val z = runBlocking { service.createReport(admin.id.value.toString(), ADMIN_WEB) }
        assertEquals(1, z.zbonNummer)
        assertTrue(signed.isEmpty())
    }

    @Test
    fun `the adminApp's counted closing is refused without a designation, and nothing is written`() {
        val e =
            assertFailsWith<PosException> {
                runBlocking { service.createReport(admin.id.value.toString(), ADMIN_WEB, countedAmount = "118.00") }
            }
        assertEquals(PosErrorCode.ZBON_SIGNER_NOT_DESIGNATED, e.code)
        transaction {
            assertEquals(0L, ZBon.all().count(), "no Z-Bon row")
            assertEquals(0L, KassenbuchHeader.all().count(), "nothing booked")
        }
        assertTrue(signed.isEmpty())
        Unit
    }

    @Test
    fun `with a designated till the adminApp's movements are signed by that till`() {
        addSecondTill()
        designate("TER-002")
        runBlocking { service.createReport(admin.id.value.toString(), ADMIN_WEB, countedAmount = "118.00") }
        assertEquals(listOf("TER-002"), signed.map { it.third })
    }

    @Test
    fun `a designated till outranks the calling till`() {
        addSecondTill()
        designate("TER-002")
        runBlocking { service.createReport(admin.id.value.toString(), DEV, countedAmount = "118.00") }
        assertEquals(listOf("TER-002"), signed.map { it.third })
    }

    @Test
    fun `an unfit designated till is a refusal, not a fallback to the calling till`() {
        addSecondTill()
        designate("TER-002")
        transaction { Terminal.find { TerminalTable.clientId eq "TER-002" }.single().isActive = false }
        val e =
            assertFailsWith<PosException> {
                runBlocking { service.createReport(admin.id.value.toString(), DEV, countedAmount = "118.00") }
            }
        assertEquals(PosErrorCode.ZBON_SIGNER_UNAVAILABLE, e.code)
        transaction { assertEquals(0L, ZBon.all().count()) }
        Unit
    }

    @Test
    fun `a configured daily Geldtransit needs a signer even on a plain adminApp closing`() {
        configureDailyTransfer()
        val e = assertFailsWith<PosException> { runBlocking { service.createReport(admin.id.value.toString(), ADMIN_WEB) } }
        assertEquals(PosErrorCode.ZBON_SIGNER_NOT_DESIGNATED, e.code)
        transaction { assertEquals(0L, ZBon.all().count(), "a refusal consumes no Z-Bon") }
        Unit
    }

    @Test
    fun `a closing at a till without a designation behaves as before`() {
        runBlocking { service.createReport(admin.id.value.toString(), DEV, countedAmount = "118.00") }
        assertEquals(listOf("client-1"), signed.map { it.third })
    }

    /** The single CREATE_REPORT audit row's after_state, parsed. */
    private fun closingAuditState(): JsonObject =
        transaction {
            val row =
                TransactionAuditLogTable
                    .selectAll()
                    .where { TransactionAuditLogTable.operation eq "CREATE_REPORT" }
                    .single()
            Json.parseToJsonElement(row[TransactionAuditLogTable.afterState]!!).jsonObject
        }

    @Test
    fun `the closing's audit row names the designated till that signed the adminApp's movements`() {
        addSecondTill()
        designate("TER-002")
        val z = runBlocking { service.createReport(admin.id.value.toString(), ADMIN_WEB, countedAmount = "118.00") }
        val state = closingAuditState()
        assertEquals("TER-002", state["signingClientId"]!!.jsonPrimitive.content)
        assertEquals(ClosingSigner.Source.DESIGNATED_TILL.name, state["signingClientSource"]!!.jsonPrimitive.content)
        assertEquals(z.zbonNummer.toString(), state["report"]!!.jsonObject["zbonNummer"]!!.jsonPrimitive.content, "the report stays recorded")
        assertEquals(signed.map { it.third }.distinct(), listOf(state["signingClientId"]!!.jsonPrimitive.content), "the audited signer is the one that signed")
    }

    @Test
    fun `the closing's audit row names the calling till when nothing is designated`() {
        runBlocking { service.createReport(admin.id.value.toString(), DEV, countedAmount = "118.00") }
        val state = closingAuditState()
        assertEquals("client-1", state["signingClientId"]!!.jsonPrimitive.content)
        assertEquals(ClosingSigner.Source.CALLING_TILL.name, state["signingClientSource"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a plain closing's audit row records that no signer was needed`() {
        runBlocking { service.createReport(admin.id.value.toString(), ADMIN_WEB) }
        val state = closingAuditState()
        assertEquals(JsonNull, state["signingClientId"])
        assertEquals(ClosingSigner.NO_SIGNER_NEEDED, state["signingClientSource"]!!.jsonPrimitive.content)
    }

    @Test
    fun `readiness at a till without a designation names the till`() {
        val r = runBlocking { service.getClosingReadiness(DEV) }
        assertEquals("Kasse 1", r.signerName)
        assertNull(r.signerProblem)
        assertFalse(r.dailyTransferConfigured)
        assertEquals("EUR", r.currency)
    }

    @Test
    fun `readiness carries the shop's configured currency`() {
        transaction {
            ShopConfigurationTable.upsert {
                it[ShopConfigurationTable.shopId] = shop.id.value
                it[ShopConfigurationTable.key] = ConfigKey.DEFAULT_CURRENCY.key
                it[ShopConfigurationTable.value] = JsonObject(mapOf("currency" to JsonPrimitive("CHF")))
                it[ShopConfigurationTable.description] = "test"
                it[ShopConfigurationTable.updatedAt] = null
            }
        }
        runBlocking { ShopConfigurationManager.refreshConfiguration() }
        assertEquals("CHF", runBlocking { service.getClosingReadiness(ADMIN_WEB) }.currency)
    }

    @Test
    fun `readiness in the adminApp without a designation reports NOT_DESIGNATED`() {
        assertEquals(ClosingSignerProblem.NOT_DESIGNATED, runBlocking { service.getClosingReadiness(ADMIN_WEB) }.signerProblem)
    }

    @Test
    fun `readiness reports a configured daily transfer`() {
        configureDailyTransfer()
        val r = runBlocking { service.getClosingReadiness(ADMIN_WEB) }
        assertTrue(r.dailyTransferConfigured)
        assertTrue(r.blocksClosing)
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
                this.name = "Kasse 1"
                this.fiscal = true
                this.role = TerminalRole.CASHIER.name
                this.shopInfo = shop
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
        transaction {
            ShopConfigurationTable.deleteWhere {
                ShopConfigurationTable.key inList listOf(ConfigKey.CASH_BOOK_TRANSFER_TARGET.key, ConfigKey.DEFAULT_CURRENCY.key)
            }
        }
        runBlocking {
            ShopConfigurationManager.refreshConfiguration()
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

package org.example.project.features.kassenbuch

import kotlinx.coroutines.runBlocking
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.core.domain.dto.document.DocumentDto
import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.dto.tse.TseConfigDto
import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.core.domain.enums.kassenbuch.CorrectionRole
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.core.domain.enums.kassenbuch.KassenbuchLineKind
import org.example.project.core.domain.enums.setup.TseMode
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
import org.example.project.features.tse.data.repository.TseTransactionRepositoryImpl
import org.example.project.features.tse.data.service.TseVorgangSigner
import org.example.project.features.tse.domain.factory.TseServiceFactory
import org.example.project.features.tse.domain.provider.TseSignProvider
import org.example.project.features.zBon.data.repository.ZBonRepositoryImpl
import org.example.project.infrastructure.di.httpClientModule
import org.example.project.infrastructure.di.reposModule
import org.example.project.infrastructure.di.serializationModule
import org.example.project.infrastructure.di.servicesModule
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
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Round-5 TSER-1: pins the EXACT `Kassenbeleg-V1` ProcessData string a Kassenbuch entry signs
 * into the WORM log.
 *
 * `KassenbuchProcessDataBuilderTest` already pins the builder in isolation, and the DSFinV-K
 * compliance gate seeds `processDataHash = "hash-$txNumber"` — placing **zero** constraint on
 * ProcessData content. Neither can catch a caller that hands the builder the wrong number, so
 * the assertion has to be an exact string captured at the real signing boundary
 * ([TseSignProvider.quickSign]).
 *
 * The finding: `KassenbuchServiceImpl` passed `request.lines.sumOf { it.amount }` — the
 * **document magnitude**, which `KassenbuchDto.validate()` forces positive because the entry
 * TYPE carries the direction — as `netCashAmount`, which is contractually the **signed cash
 * flow**. Every outgoing entry therefore signed `Beleg^-50.00_…^+50.00:Bar` (buckets negative,
 * payment positive) where Anhang I p. 119/120 mandates `^-50.00:Bar`, violating p. 116 „die
 * Summen der Umsätze mit denen der Zahlungen übereinstimmen": the signed log claimed cash flowed
 * IN for every outflow.
 *
 * `getBonTyp` returns BELEG for every entry type except CLOSING_BALANCE (rejected at creation),
 * so the Kassenbeleg branch is taken for EVERY new entry — including a correction REVERSAL
 * (CORRECTION_OUT), covered below.
 */
@DisplayName("Kassenbuch Kassenbeleg-V1 signs the SIGNED cash flow (TSER-1)")
class KassenbuchSignedProcessDataTest {
    private val DEV = "test-device"

    private lateinit var service: KassenbuchServiceImpl
    private lateinit var repository: KassenbuchRepositoryImpl
    private lateinit var employee: Employee
    private lateinit var emp: String

    /** Every ProcessData string handed to the TSE, in signing order. */
    private val signed = mutableListOf<String>()

    @BeforeEach
    fun setup() {
        TestDatabase.setup()
        GlobalContext.getOrNull()?.let { GlobalContext.stopKoin() }
        startKoin {
            modules(serializationModule, httpClientModule, reposModule, servicesModule)
        }
        signed.clear()
        repository = KassenbuchRepositoryImpl(testKassenbuchDocuments())

        TestData.createShopInfo()
        employee = TestData.createAdmin()
        emp = employee.id.value.toString()
        // F59: the outflows below need cash in the Ladenkasse (Kassenminus guard). Funded through the
        // repository, so nothing is signed: every captured ProcessData — `signed.last()`, `signed[i]` —
        // is still exactly the entry under test.
        TestData.fundCashBook("1000.00")

        // TseServiceFactory reads the GLOBAL ShopConfigurationManager singleton (not its injected
        // config manager), so the real signing path is only reachable by mutating the singleton.
        // Reset in @AfterEach — this is process-global state.
        runBlocking {
            ShopConfigurationManager.setTseConfig(
                TseConfigDto.TseConfig(
                    mode = TseMode.NORMAL,
                    timeAdminPin = "12345",
                ),
            )
        }

        service =
            KassenbuchServiceImpl(
                kassenbuchRepository = repository,
                zBonRepository = ZBonRepositoryImpl(),
                employeeRepository = EmployeeRepositoryImpl(),
                shopInfoRepository = ShopInfoRepositoryImpl(),
                tseServiceFactory =
                    TseServiceFactory(
                        tseTransactionRepository = TseTransactionRepositoryImpl(),
                        tseSignClient = capturingSignProvider,
                        signer = TseVorgangSigner(capturingSignProvider, TseTransactionRepositoryImpl(), noopProxy()),
                    ),
                tseSigningQueueService = noopProxy(),
                terminalRepository = TerminalRepositoryImpl(),
                documents = testKassenbuchDocuments(pdfGenerationService = fakePdfGenerationService, documentManagementService = fakeDocumentManagementService),
                configManager = ShopConfigurationManager,
            )

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
        // Restore the process-global singleton before the next class sees it.
        runBlocking {
            ShopConfigurationManager.setTseConfig(
                TseConfigDto.TseConfig(
                    mode = TseMode.DISABLED,
                    timeAdminPin = "12345",
                ),
            )
        }
        GlobalContext.getOrNull()?.let { GlobalContext.stopKoin() }
        TestDatabase.teardown()
    }

    // -------------------------------------------------------------------------
    // Fakes
    // -------------------------------------------------------------------------

    /** Records every quickSign ProcessData; otherwise a healthy, always-connected TSE. */
    private val capturingSignProvider =
        object : TseSignProvider {
            private var txCounter = 0L

            override suspend fun quickSign(
                processType: String,
                processData: String,
                clientId: String,
            ): TseDto.SignResultDto {
                signed += processData
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
            ) = throw NotImplementedError("Kassenbuch signs via quickSign only")

            override suspend fun updateTransaction(
                transactionNumber: Long,
                processData: String,
                clientId: String,
            ) = throw NotImplementedError("Kassenbuch signs via quickSign only")

            override suspend fun finishTransaction(
                transactionNumber: Long,
                processData: String,
                clientId: String,
                processType: String,
            ) = throw NotImplementedError("Kassenbuch signs via quickSign only")

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

    /** Throwing proxy for collaborators that must NOT be reached on the happy signing path. */
    private inline fun <reified T : Any> noopProxy(): T {
        @Suppress("UNCHECKED_CAST")
        return Proxy.newProxyInstance(
            T::class.java.classLoader,
            arrayOf(T::class.java),
        ) { _, method, _ ->
            throw NotImplementedError("Stub: ${T::class.simpleName}.${method.name} should not be called")
        } as T
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
                val id = newDocumentId("storno-eigenbeleg.pdf")
                DocumentDto.DocumentResponseDto(
                    id = id,
                    filename = "storno-eigenbeleg.pdf",
                    fileType = "INVOICE",
                    fileSize = 1024L,
                    uploadedAt = "2026-07-18T00:00:00",
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

    /**
     * Creates a single-line Kassenbuch entry and returns the ProcessData it signed.
     * [amount] is the DOCUMENT magnitude the client sends (always positive — the entry type
     * carries the direction), exactly as the route hands it to the service.
     */
    private fun captureSignedProcessDataFor(
        entryType: KassenbuchEntryType,
        rate: String?,
        amount: String,
    ): String =
        captureSignedProcessDataFor(
            entryType,
            listOf(KassenbuchDto.EntryLineDto(vatRate = rate, amount = amount)),
        )

    /** Multi-line variant — the lines are handed to the service exactly as the route does. */
    private fun captureSignedProcessDataFor(
        entryType: KassenbuchEntryType,
        lines: List<KassenbuchDto.EntryLineDto>,
    ): String =
        runBlocking {
            service.createEntry(
                employee = emp,
                deviceId = DEV,
                request =
                    KassenbuchDto.CreateKassenbuchEntryRequestDto(
                        entryType = entryType,
                        lines = lines,
                        belegtext = "TSER-1 ${entryType.name}",
                        documentId = newDocumentId("${entryType.name}.pdf"),
                    ),
            )
            signed.last()
        }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    fun `outgoing KB entry signs a NEGATIVE Bar tender (TSER-1)`() {
        // Barausgabe 50,00 € @19 % — Anhang I p. 119/120 signs this as a fully negative Beleg.
        val processData =
            captureSignedProcessDataFor(
                entryType = KassenbuchEntryType.CASH_EXPENSE,
                rate = "19",
                amount = "50.0",
            )
        assertEquals("Beleg^-50.00_0.00_0.00_0.00_0.00^-50.00:Bar", processData)
    }

    @Test
    fun `incoming KB entry still signs a POSITIVE Bar tender`() {
        // Bargeldeinlage 100,00 € at 0 % — the guard that the fix does not blanket-negate.
        val processData =
            captureSignedProcessDataFor(
                entryType = KassenbuchEntryType.CASH_DEPOSIT,
                rate = "0",
                amount = "100.0",
            )
        assertEquals("Beleg^0.00_0.00_0.00_0.00_100.00^100.00:Bar", processData)
    }

    @Test
    fun `correction REVERSAL (CORRECTION_OUT) signs a NEGATIVE Bar tender (TSER-1)`() {
        // getBonTyp returns BELEG for CORRECTION_OUT too, so the Storno of an income entry runs
        // straight through the same mis-signed branch — the widest part of the blast radius.
        val originalDoc = newDocumentId("verkauf.pdf")
        runBlocking {
            service.createEntry(
                employee = emp,
                deviceId = DEV,
                request =
                    KassenbuchDto.CreateKassenbuchEntryRequestDto(
                        entryType = KassenbuchEntryType.CASH_SALE,
                        lines = listOf(KassenbuchDto.EntryLineDto(vatRate = "19", amount = "100.0")),
                        belegtext = "Verkauf",
                        documentId = originalDoc,
                    ),
            )
        }
        assertEquals("Beleg^100.00_0.00_0.00_0.00_0.00^100.00:Bar", signed.last(), "original income entry")

        val originalId =
            transaction {
                KassenbuchHeader
                    .all()
                    .orderBy(KassenbuchHeaderTable.id to SortOrder.DESC)
                    .first()
                    .id.value
            }
        runBlocking {
            service.createCorrection(
                employeeId = emp,
                deviceId = DEV,
                headerId = originalId,
                request =
                    KassenbuchDto.CorrectEntryRequestDto(
                        reason = "typo",
                        correctedEntry =
                            KassenbuchDto.CreateKassenbuchEntryRequestDto(
                                entryType = KassenbuchEntryType.CASH_SALE,
                                lines = listOf(KassenbuchDto.EntryLineDto(vatRate = "19", amount = "90.0")),
                                belegtext = "Verkauf korrigiert",
                                documentId = null,
                            ),
                    ),
            )
        }

        val reversal =
            transaction {
                repository.findCorrectionsFor(originalId).first { it.correctionRole == CorrectionRole.REVERSAL }
            }
        assertEquals(KassenbuchEntryType.CORRECTION_OUT, reversal.entryType)

        // createCorrection signs the REVERSAL first, then the corrected re-entry.
        assertEquals(3, signed.size, "original + reversal + corrected re-entry")
        assertEquals("Beleg^-100.00_0.00_0.00_0.00_0.00^-100.00:Bar", signed[1], "REVERSAL")
        assertEquals("Beleg^90.00_0.00_0.00_0.00_0.00^90.00:Bar", signed[2], "corrected re-entry")
    }

    @Test
    fun `buckets and Bar tender come from the SAME per-line conversion - no rounding drift`() {
        // 50.005 rounds HALF_UP to 50.01 in toBigDecimal(Double), while ProcessDataBuilder.fmtAmount
        // truncates (DOWN). Summing the ALREADY-CONVERTED line values (rather than negating the raw
        // document total) keeps Σ Umsätze == Σ Zahlungen by construction — Anhang I p. 116.
        val processData =
            captureSignedProcessDataFor(
                entryType = KassenbuchEntryType.CASH_EXPENSE,
                rate = "19",
                amount = "50.005",
            )
        assertEquals("Beleg^-50.01_0.00_0.00_0.00_0.00^-50.01:Bar", processData)

        val buckets = processData.substringAfter("Beleg^").substringBefore("^")
        val payment = processData.substringAfterLast("^").removeSuffix(":Bar")
        assertEquals(
            buckets.split("_").sumOf { java.math.BigDecimal(it) },
            java.math.BigDecimal(payment),
            "Σ Umsätze must equal Σ Zahlungen",
        )
        assertTrue(payment.substringAfter('.').length == 2, "Anhang I mandates exactly two decimals")
    }

    // -------------------------------------------------------------------------
    // Round-7 C1/C2: the tax container is a function of (GV_TYP, declared rate),
    // NOT of the declared rate alone.
    //
    // Nine GV_TYPs are pinned by the spec to the 0 %/nicht-steuerbar class and DISCARD the
    // declared rate. `EntryLineState.vatRate = "19"` is the CLIENT DEFAULT, so every one of
    // these entry types booked without touching the VAT dropdown signed a FALSE §12 Abs. 1
    // Umsatz declaration into the WORM log. These assertions are PER BUCKET on purpose — a
    // Σ-level assertion passes vacuously while the placement is wrong, which is exactly why
    // C1 survived four audit rounds.
    // -------------------------------------------------------------------------

    @Test
    fun `Privateinlage at the client-default 19 percent signs container 5 (C1)`() {
        // Anhang I p. 119: „Privateinlage 100€ bar" -> Beleg^0.00_0.00_0.00_0.00_100.00^100.00:Bar
        // Anhang C p. 58: „Aus umsatzsteuerlicher Sicht handelt es sich bei der Privateinlage von
        // Barmitteln um Geschäftsvorfälle ohne USt-Bezug (ID 5)."
        val processData =
            captureSignedProcessDataFor(
                entryType = KassenbuchEntryType.CASH_DEPOSIT,
                rate = "19",
                amount = "100.0",
            )
        assertEquals("Beleg^0.00_0.00_0.00_0.00_100.00^100.00:Bar", processData)
    }

    @Test
    fun `Privatentnahme at the client-default 19 percent signs container 5 (C1)`() {
        // Anhang I p. 119: „Privatentnahme 100€" -> Beleg^0.00_0.00_0.00_0.00_-100.00^-100.00:Bar
        val processData =
            captureSignedProcessDataFor(
                entryType = KassenbuchEntryType.PRIVATE_WITHDRAWAL,
                rate = "19",
                amount = "100.0",
            )
        assertEquals("Beleg^0.00_0.00_0.00_0.00_-100.00^-100.00:Bar", processData)
    }

    @Test
    fun `Geldtransit at the client-default 19 percent signs container 5 in both directions (C1)`() {
        // Anhang I p. 120 (Einzahlung auf das Geschäftsbankkonto):
        //   Beleg^0.00_0.00_0.00_0.00_-100.00^-100.00:Bar / GV_TYP: „Geldtransit"
        // Anhang C p. 58: „Der Geschäftsvorfalltyp „Geldtransit" hat aus umsatzsteuerlicher
        // Sicht keine Relevanz."
        assertEquals(
            "Beleg^0.00_0.00_0.00_0.00_-100.00^-100.00:Bar",
            captureSignedProcessDataFor(KassenbuchEntryType.CASH_TRANSFER_OUT, "19", "100.0"),
        )
        assertEquals(
            "Beleg^0.00_0.00_0.00_0.00_250.00^250.00:Bar",
            captureSignedProcessDataFor(KassenbuchEntryType.CASH_TRANSFER_IN, "19", "250.0"),
        )
        // Anhang C p. 56: „Das Auffüllen des Bargeldbestandes ist über den Geschäftsvorfalltyp
        // „Geldtransit" zu erfassen." — CHANGE_FUND maps to Geldtransit too.
        assertEquals(
            "Beleg^0.00_0.00_0.00_0.00_50.00^50.00:Bar",
            captureSignedProcessDataFor(KassenbuchEntryType.CHANGE_FUND, "19", "50.0"),
        )
    }

    /** The persisted `ust_satz` of every line of the newest entry — what the detail view and DATEV XML read. */
    private fun storedLineRatesOfNewestEntry(): List<String?> =
        transaction {
            KassenbuchHeader
                .all()
                .orderBy(KassenbuchHeaderTable.id to SortOrder.DESC)
                .first()
                .lineItems
                .map { it.ustSatz }
        }

    @Test
    fun `Geldtransit sent at the client-default 19 percent is STORED as 0 - not only signed as container 5`() {
        // Production bug: a Bankeinzahlung showed „Position 1 | 19 %" in the detail view and carried
        // <tax>19.00</tax> in the DATEV XML, because only the signed payload and the CSV discarded the rate.
        for (type in listOf(KassenbuchEntryType.CASH_TRANSFER_OUT, KassenbuchEntryType.CASH_TRANSFER_IN)) {
            captureSignedProcessDataFor(type, "19", "100.0")
            assertEquals(listOf<String?>("0"), storedLineRatesOfNewestEntry(), "$type header + line rate")
        }
    }

    @Test
    fun `every non-taxable entry type stores 0, a rate-governed one keeps its declared rate`() {
        for (type in listOf(
            KassenbuchEntryType.PRIVATE_DEPOSIT,
            KassenbuchEntryType.PRIVATE_WITHDRAWAL,
            KassenbuchEntryType.CASH_DEPOSIT,
            KassenbuchEntryType.CHANGE_FUND,
        )) {
            captureSignedProcessDataFor(type, "7", "10.0")
            assertEquals(listOf<String?>("0"), storedLineRatesOfNewestEntry(), "$type")
        }
        captureSignedProcessDataFor(KassenbuchEntryType.CASH_EXPENSE, "7", "10.0")
        assertEquals(listOf<String?>("7"), storedLineRatesOfNewestEntry(), "Auszahlung is rate-governed")
    }

    @Test
    fun `a Storno of a legacy Geldtransit stored at 19 percent is stored at 0`() {
        // Legacy row: written before the server pinned the rate. Seeded straight through the repository,
        // exactly as the old service stored it, and left untouched — only the correction is new data.
        val legacyId =
            transaction {
                repository
                    .createEntry(
                        shop = ShopInfo.all().first(),
                        employee = employee,
                        headerData =
                            KassenbuchDto.KassenbuchData(
                                entryType = KassenbuchEntryType.CASH_TRANSFER_OUT,
                                waehrung = "EUR",
                                belegNr = "LEGACY-1",
                                vorzBetrag = "-915.00",
                                belegDatum = "01.10.2026",
                                belegtext = "Bankeinzahlung",
                                uStSatz = "19",
                            ),
                        lineItemsData = listOf(KassenbuchDto.LineItemData(vorzBetrag = "-915.00", ustSatz = "19")),
                    ).id.value
            }
        runBlocking {
            service.createCorrection(
                employeeId = emp,
                deviceId = DEV,
                headerId = legacyId,
                request =
                    KassenbuchDto.CorrectEntryRequestDto(
                        reason = "wrong bank",
                        replacementDocumentId = newDocumentId("legacy-replacement.pdf"),
                        correctedEntry =
                            KassenbuchDto.CreateKassenbuchEntryRequestDto(
                                entryType = KassenbuchEntryType.CASH_TRANSFER_OUT,
                                lines = listOf(KassenbuchDto.EntryLineDto(vatRate = "19", amount = "915.0")),
                                belegtext = "Bankeinzahlung korrigiert",
                                documentId = null,
                            ),
                    ),
            )
        }
        val reversalRates =
            transaction {
                repository
                    .findCorrectionsFor(legacyId)
                    .first { it.correctionRole == CorrectionRole.REVERSAL }
                    .lineItems
                    .map { it.ustSatz }
            }
        assertEquals(listOf<String?>("0"), reversalRates, "reversal of a Geldtransit")
        val legacyRate =
            transaction {
                KassenbuchHeader
                    .findById(legacyId)!!
                    .lineItems
                    .first()
                    .ustSatz
            }
        assertEquals("19", legacyRate, "legacy row untouched")
    }

    @Test
    fun `DifferenzSollIst at the client-default 19 percent signs container 5 (C1, G2 house rule)`() {
        // No Anhang I example exists for DifferenzSollIst (Anhang C p. 59 explicitly declines to
        // qualify it). HOUSE DECISION: container 5, matching official workbook V_2_3_07
        // (UST_SCHLUESSEL 5 / BON_BRUTTO -87 / BON_UST 0 / ZAHLART_TYP Bar -87) and the key the
        // CSV exporter has always booked. Documented as a house rule, not as compliance.
        assertEquals(
            "Beleg^0.00_0.00_0.00_0.00_-87.00^-87.00:Bar",
            captureSignedProcessDataFor(KassenbuchEntryType.CASH_SHORTAGE, "19", "87.0"),
        )
        assertEquals(
            "Beleg^0.00_0.00_0.00_0.00_12.00^12.00:Bar",
            captureSignedProcessDataFor(KassenbuchEntryType.CASH_SURPLUS, "19", "12.0"),
        )
    }

    @Test
    fun `Mehrzweckgutschein redemption signs container 5 while the Umsatz lines keep their rates (C2)`() {
        // Anhang I p. 118, verbatim:
        //   „Gutscheineinlösung: Mehrzweckgutschein 100€, Versteuerung bei Einlösung,
        //    Rechnungssaldo 120€ (60€ zu 19% und 60€ zu 7%), Rest Barzahlung"
        //   Beleg^60.00_60.00_0.00_0.00_-100.00^20.00:Bar
        //   GV_TYP: „MehrzweckgutscheinEinloesung" (0%) -100€; GV_TYP: „Umsatz" (19%) 60€; (7%) 60€
        // The rate containers keep their FULL gross — nothing nets across containers — and the
        // redemption is an explicit negative in container 5 even though its line declares 19 %.
        val processData =
            captureSignedProcessDataFor(
                entryType = KassenbuchEntryType.CASH_SALE,
                lines =
                    listOf(
                        KassenbuchDto.EntryLineDto(vatRate = "19", amount = "60.0"),
                        KassenbuchDto.EntryLineDto(vatRate = "7", amount = "60.0"),
                        KassenbuchDto.EntryLineDto(
                            kind = KassenbuchLineKind.GUTSCHEIN,
                            vatRate = "19",
                            amount = "-100.0",
                        ),
                    ),
            )
        assertEquals("Beleg^60.00_60.00_0.00_0.00_-100.00^20.00:Bar", processData)
    }

    @Test
    fun `mixed-rate Umsatz entry splits across containers 1 and 2 - nothing nets across`() {
        // Anhang I p. 118: „50€ Umsatz 19%, 50€ Umsatz 7%" -> Beleg^50.00_50.00_0.00_0.00_0.00.
        // ONE GV_TYP, TWO containers, one Vorgang — the counter-example proving the container is
        // NOT a function of GV_TYP alone.
        val processData =
            captureSignedProcessDataFor(
                entryType = KassenbuchEntryType.CASH_SALE,
                lines =
                    listOf(
                        KassenbuchDto.EntryLineDto(vatRate = "19", amount = "50.0"),
                        KassenbuchDto.EntryLineDto(vatRate = "7", amount = "50.0"),
                    ),
            )
        assertEquals("Beleg^50.00_50.00_0.00_0.00_0.00^100.00:Bar", processData)
    }

    @Test
    fun `Auszahlung keeps the DECLARED rate container - CASH_EXPENSE and CASH_WITHDRAWAL alike`() {
        // REGRESSION GUARD. Anhang I p. 121: „Barausgabe: Büromaterial 50€ zu 19%" ->
        // Beleg^-50.00_0.00_0.00_0.00_0.00^-50.00:Bar / GV_TYP: „Auszahlung". A rate container
        // legitimately goes negative — the field is „Brutto-Steuerumsätze je Steuersatz", not
        // revenue. `getGvTyp` maps CASH_WITHDRAWAL to the SAME `Auszahlung`, so the spec cannot
        // tell our two entry types apart: both must land in the declared-rate container.
        assertEquals(
            "Beleg^-50.00_0.00_0.00_0.00_0.00^-50.00:Bar",
            captureSignedProcessDataFor(KassenbuchEntryType.CASH_EXPENSE, "19", "50.0"),
        )
        assertEquals(
            "Beleg^-50.00_0.00_0.00_0.00_0.00^-50.00:Bar",
            captureSignedProcessDataFor(KassenbuchEntryType.CASH_WITHDRAWAL, "19", "50.0"),
        )
        // A supplier invoice paid cash, goods at 19 % and 7 %: BOTH rate containers go negative
        // and Σ containers == Σ Zahlungen (p. 116).
        assertEquals(
            "Beleg^-119.00_-107.00_0.00_0.00_0.00^-226.00:Bar",
            captureSignedProcessDataFor(
                entryType = KassenbuchEntryType.CASH_EXPENSE,
                lines =
                    listOf(
                        KassenbuchDto.EntryLineDto(vatRate = "19", amount = "119.0"),
                        KassenbuchDto.EntryLineDto(vatRate = "7", amount = "107.0"),
                    ),
            ),
        )
    }

    @Test
    fun `a correction of a container-5 entry is an exact container-wise negation`() {
        // Tz. 4.2.2 p. 35 „Die Vorzeichen sind umzukehren" + BON_STORNO p. 85 „…rechnerisch
        // wieder ausgleicht". Since nothing nets ACROSS containers, the reversal is only able to
        // „ausgleichen" if it occupies the SAME container as the original. Before the fix the
        // original and its reversal both sat in container 1 for a Privateinlage.
        val originalDoc = newDocumentId("einlage.pdf")
        runBlocking {
            service.createEntry(
                employee = emp,
                deviceId = DEV,
                request =
                    KassenbuchDto.CreateKassenbuchEntryRequestDto(
                        entryType = KassenbuchEntryType.CASH_DEPOSIT,
                        lines = listOf(KassenbuchDto.EntryLineDto(vatRate = "19", amount = "200.0")),
                        belegtext = "Privateinlage",
                        documentId = originalDoc,
                    ),
            )
        }
        assertEquals("Beleg^0.00_0.00_0.00_0.00_200.00^200.00:Bar", signed.last(), "original")

        val originalId =
            transaction {
                KassenbuchHeader
                    .all()
                    .orderBy(KassenbuchHeaderTable.id to SortOrder.DESC)
                    .first()
                    .id.value
            }
        runBlocking {
            service.createCorrection(
                employeeId = emp,
                deviceId = DEV,
                headerId = originalId,
                request =
                    KassenbuchDto.CorrectEntryRequestDto(
                        reason = "booked twice",
                        correctedEntry =
                            KassenbuchDto.CreateKassenbuchEntryRequestDto(
                                entryType = KassenbuchEntryType.CASH_DEPOSIT,
                                lines = listOf(KassenbuchDto.EntryLineDto(vatRate = "19", amount = "150.0")),
                                belegtext = "Privateinlage korrigiert",
                                documentId = null,
                            ),
                    ),
            )
        }
        val reversal =
            transaction {
                repository.findCorrectionsFor(originalId).first { it.correctionRole == CorrectionRole.REVERSAL }
            }
        assertEquals(KassenbuchEntryType.CORRECTION_OUT, reversal.entryType)
        assertEquals("Beleg^0.00_0.00_0.00_0.00_-200.00^-200.00:Bar", signed[1], "REVERSAL")
        assertEquals("Beleg^0.00_0.00_0.00_0.00_150.00^150.00:Bar", signed[2], "corrected re-entry")
    }
}

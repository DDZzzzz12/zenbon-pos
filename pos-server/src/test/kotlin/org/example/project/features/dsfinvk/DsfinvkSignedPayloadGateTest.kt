package org.example.project.features.dsfinvk

import kotlinx.coroutines.runBlocking
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.core.domain.dto.document.DocumentDto
import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.dto.tse.TseConfigDto
import org.example.project.core.domain.dto.tse.TseDto
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
import org.example.project.features.setup.data.repository.ShopInfoRepositoryImpl
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
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import java.lang.reflect.Proxy
import kotlin.test.assertEquals

/**
 * Task 10a — the signed-payload half of the DSFinV-K release gate.
 *
 * `DsfinvkComplianceGateTest` stubs `processDataHash = "hash-$txNumber"` and asserts nothing about
 * the signed `Kassenbeleg-V1` string. That is coverage gap 4, and it is *why* C1/C2 — the
 * entry-type-blind bucketing that signed a false §12 Abs. 1 Umsatz declaration into the WORM log
 * for every Privateinlage/Geldtransit/Privatentnahme/DifferenzSollIst/Mehrzweckgutschein booked at
 * the client's `vatRate = "19"` default — would have passed a fully green gate. This gate signs ONE
 * Vorgang of each entry-type family through the **real** `KassenbuchServiceImpl` path (the built
 * ProcessData captured at [TseSignProvider.quickSign], exactly as `KassenbuchSignedProcessDataTest`
 * does) and asserts the exact per-bucket string the Anhang-I examples mandate.
 *
 * **Per-bucket, never Σ-level.** A Σ-container == Σ-payment assertion passes vacuously for a
 * 19 %-defaulted Privateinlage (100 in container 1 still balances Bar 100), which is precisely how
 * C1 survived four audit rounds. Each string below pins the CONTAINER the amount lands in.
 *
 * **The bucket is `(GV_TYP, declared rate)`, not the declared rate alone** — the ruling of the
 * approved two-derivation table in `docs/audits/dsfinvk-tse/kassenbuch-bucket-table.md`. Nine
 * GV_TYPs (Privateinlage, Privatentnahme, Geldtransit, DifferenzSollIst, MehrzweckgutscheinEinloesung,
 * Anfangsbestand, …) discard the declared rate into container 5; Umsatz and Auszahlung keep it. The
 * expected strings here are transcribed from Anhang I p. 117–121, NOT re-derived from the builder,
 * so a builder that reverts to rate-only bucketing turns every container-5 family RED here.
 *
 * Sibling of `KassenbuchSignedProcessDataTest` (the per-fix TSER-1/C1/C2 suite) on purpose: this is
 * the STANDING gate that must stay green for release, re-exercising the signed families the way the
 * CSV gate re-exercises the whole audit from one fixture.
 */
@DisplayName("DSFinV-K signed-payload gate — per-bucket Kassenbeleg-V1 (Task 10a)")
class DsfinvkSignedPayloadGateTest {
    private val DEV = "test-device"

    private lateinit var service: KassenbuchServiceImpl
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

        TestData.createShopInfo()
        employee = TestData.createAdmin()
        emp = employee.id.value.toString()
        // F59: the outflow families need cash in the Ladenkasse (Kassenminus guard). Funded through
        // the repository — unsigned, so `signed` still holds only the entries under test.
        TestData.fundCashBook("1000.00")

        // TseServiceFactory reads the GLOBAL ShopConfigurationManager singleton, so the real
        // signing path is only reachable by mutating the singleton. Reset in @AfterEach.
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
                kassenbuchRepository = KassenbuchRepositoryImpl(testKassenbuchDocuments()),
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
    // Fakes (identical contract to KassenbuchSignedProcessDataTest)
    // -------------------------------------------------------------------------

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

    private fun sign(
        entryType: KassenbuchEntryType,
        rate: String?,
        amount: String,
    ): String = sign(entryType, listOf(KassenbuchDto.EntryLineDto(vatRate = rate, amount = amount)))

    private fun sign(
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
                        belegtext = "gate ${entryType.name}",
                        documentId = newDocumentId("${entryType.name}.pdf"),
                    ),
            )
            signed.last()
        }

    // -------------------------------------------------------------------------
    // Per-family per-bucket invariants
    // -------------------------------------------------------------------------

    /** Umsatz — RATE-governed. Anhang I p. 117: „100€ Umsatz 19%, Bar bezahlt". */
    @Test
    fun `family Umsatz keeps the declared rate container`() {
        assertEquals(
            "Beleg^100.00_0.00_0.00_0.00_0.00^100.00:Bar",
            sign(KassenbuchEntryType.CASH_SALE, "19", "100.0"),
        )
        // Anhang I p. 118: „50€ Umsatz 19%, 50€ Umsatz 7%" — one GV_TYP, two containers, nothing
        // nets across (the counter-example proving the container is NOT a function of GV_TYP alone).
        assertEquals(
            "Beleg^50.00_50.00_0.00_0.00_0.00^100.00:Bar",
            sign(
                KassenbuchEntryType.CASH_SALE,
                listOf(
                    KassenbuchDto.EntryLineDto(vatRate = "19", amount = "50.0"),
                    KassenbuchDto.EntryLineDto(vatRate = "7", amount = "50.0"),
                ),
            ),
        )
    }

    /**
     * A generic deposit — CASH_DEPOSIT → GV_TYP Einzahlung (m6) — still signs container 5 at the
     * client's 19 % default. Anhang C p. 58 leaves Einzahlung's USt „bezogen auf den jeweiligen
     * Sachverhalt zu prüfen", so it is a HOUSE container-5 decision (KassenbuchTaxClassifier). The
     * point of this assertion is that the m6 relabel Privateinlage→Einzahlung changed the GV_TYP,
     * NOT the signed payload — the bytes are identical to before, so no WORM signature shifted.
     */
    @Test
    fun `a generic deposit still signs container 5 at the 19 percent default (m6)`() {
        assertEquals(
            "Beleg^0.00_0.00_0.00_0.00_100.00^100.00:Bar",
            sign(KassenbuchEntryType.CASH_DEPOSIT, "19", "100.0"),
        )
    }

    /**
     * An owner deposit — PRIVATE_DEPOSIT → GV_TYP Privateinlage (m6) — also signs container 5.
     * Anhang I p. 119 „Privateinlage 100€ bar" → `Beleg^0.00_0.00_0.00_0.00_100.00^100.00:Bar`;
     * Anhang C p. 58 „ohne USt-Bezug (ID 5)". This is SPEC container 5, not a house decision.
     */
    @Test
    fun `family Privateinlage signs container 5 at the 19 percent default (C1)`() {
        assertEquals(
            "Beleg^0.00_0.00_0.00_0.00_100.00^100.00:Bar",
            sign(KassenbuchEntryType.PRIVATE_DEPOSIT, "19", "100.0"),
        )
    }

    /** Privatentnahme — container 5, negative. Anhang I p. 119: „Privatentnahme 100€". */
    @Test
    fun `family Privatentnahme signs container 5 negative at the 19 percent default (C1)`() {
        assertEquals(
            "Beleg^0.00_0.00_0.00_0.00_-100.00^-100.00:Bar",
            sign(KassenbuchEntryType.PRIVATE_WITHDRAWAL, "19", "100.0"),
        )
    }

    /**
     * Geldtransit — container 5, both directions. Anhang I p. 120 (Einzahlung auf das
     * Geschäftsbankkonto): `Beleg^0.00_0.00_0.00_0.00_-100.00^-100.00:Bar`; Anhang C p. 58: „hat
     * aus umsatzsteuerlicher Sicht keine Relevanz".
     */
    @Test
    fun `family Geldtransit signs container 5 in both directions at the 19 percent default (C1)`() {
        assertEquals(
            "Beleg^0.00_0.00_0.00_0.00_-100.00^-100.00:Bar",
            sign(KassenbuchEntryType.CASH_TRANSFER_OUT, "19", "100.0"),
        )
        assertEquals(
            "Beleg^0.00_0.00_0.00_0.00_250.00^250.00:Bar",
            sign(KassenbuchEntryType.CASH_TRANSFER_IN, "19", "250.0"),
        )
    }

    /**
     * Barausgabe / Auszahlung — RATE-governed and legitimately negative. Anhang I p. 121:
     * „Barausgabe: Büromaterial 50€ zu 19%" → `Beleg^-50.00_0.00_0.00_0.00_0.00^-50.00:Bar`. The
     * field is „Brutto-Steuerumsätze je Steuersatz", not revenue, so a rate container may go
     * negative. `getGvTyp` maps CASH_WITHDRAWAL to the SAME `Auszahlung`, so the spec cannot tell
     * the two entry types apart — both land in the declared-rate container (the exporter's E1
     * direction, where the CSV side was the wrong one before Task 1).
     */
    @Test
    fun `family Auszahlung keeps the declared rate container, CASH_EXPENSE and CASH_WITHDRAWAL alike`() {
        assertEquals(
            "Beleg^-50.00_0.00_0.00_0.00_0.00^-50.00:Bar",
            sign(KassenbuchEntryType.CASH_EXPENSE, "19", "50.0"),
        )
        assertEquals(
            "Beleg^-50.00_0.00_0.00_0.00_0.00^-50.00:Bar",
            sign(KassenbuchEntryType.CASH_WITHDRAWAL, "19", "50.0"),
        )
    }

    /**
     * Mehrzweckgutschein redemption — container 5 while the rate containers keep their FULL gross.
     * Anhang I p. 118: „Mehrzweckgutschein 100€ … Rechnungssaldo 120€ (60€ zu 19% und 60€ zu 7%),
     * Rest Barzahlung" → `Beleg^60.00_60.00_0.00_0.00_-100.00^20.00:Bar`. The −100 sits in container
     * 5 even though its line declares 19 %, and does NOT net against the 60,00s (C2).
     */
    @Test
    fun `family Mehrzweckgutschein signs container 5 while Umsatz lines keep their rates (C2)`() {
        assertEquals(
            "Beleg^60.00_60.00_0.00_0.00_-100.00^20.00:Bar",
            sign(
                KassenbuchEntryType.CASH_SALE,
                listOf(
                    KassenbuchDto.EntryLineDto(vatRate = "19", amount = "60.0"),
                    KassenbuchDto.EntryLineDto(vatRate = "7", amount = "60.0"),
                    KassenbuchDto.EntryLineDto(
                        kind = KassenbuchLineKind.GUTSCHEIN,
                        vatRate = "19",
                        amount = "-100.0",
                    ),
                ),
            ),
        )
    }
}

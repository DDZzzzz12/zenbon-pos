package org.example.project.features.dsfinvk

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.example.project.core.domain.dto.document.DocumentDto
import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto
import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto.BusinessCaseTypes
import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.enums.kassenbuch.CorrectionRole
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.export.dsfinvk.builder.KassenbuchDsfinvkMapping
import org.example.project.export.dsfinvk.builder.KassenbuchTaxClassifier
import org.example.project.features.document.domain.models.Document
import org.example.project.features.document.domain.service.DocumentManagementService
import org.example.project.features.document.domain.service.PdfGenerationService
import org.example.project.features.dsfinvk.data.service.DsfinvkExportServiceImpl
import org.example.project.features.employee.data.repository.EmployeeRepositoryImpl
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.kassenbuch.data.repository.KassenbuchRepositoryImpl
import org.example.project.features.kassenbuch.data.service.KassenbuchServiceImpl
import org.example.project.features.kassenbuch.domain.models.KassenbuchHeader
import org.example.project.features.salesinvoice.data.service.SalesInvoiceBillInvoiceLink
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.features.setup.data.repository.ShopInfoRepositoryImpl
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.terminal.data.repository.TerminalRepositoryImpl
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.features.tse.data.service.TseVorgangSigner
import org.example.project.features.zBon.data.repository.ZBonRepositoryImpl
import org.example.project.features.zBon.domain.models.ZBon
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.example.project.testutils.testKassenbuchDocuments
import org.example.project.utils.formatter.currentTimeStamp
import org.example.project.utils.formatter.toDecimalString
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Round-5 C6: a Kassenbuch correction must unwind the corrected entry's VAT.
 *
 * `KassenbuchDsfinvkMapping.getGvTyp` resolves a `CORRECTION_IN`/`CORRECTION_OUT` row through
 * its `correctsHeader` link to the corrected entry's GV_TYP, but the entry-level
 * `hasVatImplication` (deleted in round 7, replaced by `KassenbuchTaxClassifier`) used to
 * fall through to `else -> false` for those two types. All three Kassenbuch VAT producers
 * therefore booked a correction's lines at UST_SCHLUESSEL 5 / USt 0,00 while the original sat
 * at key 1 — the original's Vorsteuer/Umsatzsteuer was never unwound and the Kassenabschluss
 * over-declared by exactly the corrected entry's VAT.
 *
 * Because ALL THREE producers were wrong together, cross-producer agreement (the invariant that
 * caught round-4 INV-1/INV-2) reported perfect consistency and passed both before and after the
 * fix. Every expected value below is therefore an ABSOLUTE figure derived from the DSFinV-K
 * decomposition of the fixture (119,00 € @ 19 % = 100,00 net + 19,00 USt), never a comparison
 * between two producers.
 *
 * The fixture is built through the REAL `KassenbuchServiceImpl.createCorrection` — the same
 * server entry point the client's "correct entry" action calls — so the corrected/reversal pair
 * is provably reachable rather than a hand-assembled state.
 */
@DisplayName("DSFinV-K Kassenbuch correction VAT (round-5 C6)")
class DsfinvkKassenbuchCorrectionVatTest {
    private val dev = "test-device"

    private lateinit var service: KassenbuchServiceImpl
    private lateinit var repository: KassenbuchRepositoryImpl
    private lateinit var shop: ShopInfo
    private lateinit var employee: Employee
    private lateinit var emp: String
    private lateinit var zBon: ZBon

    private val exportService =
        DsfinvkExportServiceImpl(
            json = Json { ignoreUnknownKeys = true },
            configRepository = ConfigurationRepository(),
            invoices = SalesInvoiceBillInvoiceLink(),
        )

    @BeforeEach
    fun setup() {
        TestDatabase.setup()
        repository = KassenbuchRepositoryImpl(testKassenbuchDocuments())

        // Real TseServiceFactory: with no global TSE config it falls back to DISABLED mode, whose
        // signTransaction returns null. The repo/client collaborators are never touched there.
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
        zBon = TestData.createZBonFullDay(shop, employee)
        // F59: a correction's re-entry is guarded by the Kassenminus rule. The seeded originals are
        // written straight to the book, so a re-booked expense needs cash there; the producers below
        // read only the original/reversal/re-entry ids, never this deposit.
        TestData.fundCashBook("1000.00")

        transaction {
            Terminal.new {
                this.deviceId = dev
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
    fun teardown() = TestDatabase.teardown()

    // -------------------------------------------------------------------------
    // Fakes (same shape as KassenbuchCorrectionServiceTest)
    // -------------------------------------------------------------------------

    private inline fun <reified T : Any> noopProxy(): T {
        @Suppress("UNCHECKED_CAST")
        return Proxy.newProxyInstance(
            T::class.java.classLoader,
            arrayOf(T::class.java),
        ) { _, method, _ ->
            throw NotImplementedError("Stub: ${T::class.simpleName}.${method.name} must not be called")
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
                // The money DTOs now carry the shop currency, so mappers resolve it on these paths.
                "getDefaultCurrency" -> "EUR"
                else -> throw NotImplementedError(
                    "Stub: IShopConfigurationManager.${method.name} must not be called",
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
                throw NotImplementedError("PdfGenerationService.${method.name} must not be called")
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
                throw NotImplementedError("DocumentManagementService.${method.name} must not be called")
            }
        } as DocumentManagementService
    }

    // -------------------------------------------------------------------------
    // Fixture helpers
    // -------------------------------------------------------------------------

    private var belegCounter = 0

    /**
     * Seeds an ORIGINAL entry with a single VAT-bearing line, in cash-flow sign.
     *
     * [gross] is the signed cash movement (negative for an outgoing `CASH_EXPENSE`), matching
     * how `DsfinvkKassenbuchPerLineGvTypTest` seeds manual entries.
     */
    @Suppress("DefaultLocale")
    private fun seedOriginal(
        entryType: KassenbuchEntryType,
        gross: String,
        vatRate: String,
    ): Long {
        val docId = newDocumentId("original.pdf")
        val belegNr = "2026-07-${String.format("%04d", ++belegCounter)}"
        return transaction {
            val header =
                repository.createEntry(
                    shop = shop,
                    employee = employee,
                    headerData =
                        KassenbuchDto.KassenbuchData(
                            entryType = entryType,
                            belegNr = belegNr,
                            vorzBetrag = gross,
                            belegDatum = "18.07.2026",
                            belegtext = "Original entry",
                        ),
                    lineItemsData = listOf(KassenbuchDto.LineItemData(vorzBetrag = gross, ustSatz = vatRate)),
                )
            repository.linkDocument(header.id.value, docId)
            header.id.value
        }
    }

    /** The ids of the three correction rows of [originalId]: original, REVERSAL, RE_ENTRY. */
    private data class Triple3(
        val original: Long,
        val reversal: Long,
        val reEntry: Long,
    )

    private fun loadTriple(originalId: Long): Triple3 =
        transaction {
            val corrections = repository.findCorrectionsFor(originalId)
            Triple3(
                original = originalId,
                reversal = corrections.single { it.correctionRole == CorrectionRole.REVERSAL }.id.value,
                reEntry = corrections.single { it.correctionRole == CorrectionRole.RE_ENTRY }.id.value,
            )
        }

    private fun bonId(entryId: Long) = KassenbuchDsfinvkMapping.generateBonId(entryId)

    /**
     * The export producers walk Exposed relations lazily (`entry.zBon`, `entry.lineItems`,
     * `entry.correctsHeader`), so they MUST run inside an ambient transaction — the same
     * convention `DsfinvkKassenbuchPerLineGvTypTest` follows. Each helper re-resolves the headers
     * from their ids inside that transaction rather than reusing detached entities.
     */
    private fun <T> withHeaders(
        ids: LongArray,
        block: (List<KassenbuchHeader>) -> T,
    ): T =
        transaction {
            block(ids.map { repository.getEntryById(it)!! })
        }

    private fun linesVat(vararg ids: Long) =
        withHeaders(ids) {
            exportService.generateAvLineVats(emptyList(), emptyList(), it, listOf(zBon), "K1")
        }

    private fun transactionsVat(vararg ids: Long): List<DsfinvkExportDto.BonkopfUstRowDto> =
        withHeaders(ids) {
            val sink = mutableListOf<DsfinvkExportDto.BonkopfUstRowDto>()
            exportService.appendKassenbuchBonkopfUst(it, listOf(zBon), "K1", sink)
            sink
        }

    private fun businessCases(vararg ids: Long) =
        withHeaders(ids) {
            exportService.aggregateKassenbuchBusinessCases(it, listOf(zBon), "K1")
        }

    private fun sumOf(values: List<String>): BigDecimal = values.fold(BigDecimal.ZERO) { acc, s -> acc + BigDecimal(s) }

    // -------------------------------------------------------------------------
    // C6 — the reversal of a VAT-bearing Barausgabe must unwind the Vorsteuer
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("CORRECTION_IN reversing a 19% CASH_EXPENSE unwinds the Vorsteuer in all three VAT producers")
    fun correctionInUnwindsExpenseVat() =
        runBlocking {
            // Barausgabe of 119,00 EUR brutto @ 19 % - cash leaves the drawer, so the signed
            // cash-flow amount is negative. Decomposition (DSFinV-K Anhang C):
            //   brutto -119.00 = netto -100.00 + USt -19.00, UST_SCHLUESSEL 1.
            val originalId = seedOriginal(KassenbuchEntryType.CASH_EXPENSE, gross = "-119.00", vatRate = "19")

            service.createCorrection(
                employeeId = emp,
                deviceId = dev,
                headerId = originalId,
                request =
                    KassenbuchDto.CorrectEntryRequestDto(
                        reason = "Wrong supplier invoice",
                        correctedEntry =
                            KassenbuchDto.CreateKassenbuchEntryRequestDto(
                                entryType = KassenbuchEntryType.CASH_EXPENSE,
                                lines = listOf(KassenbuchDto.EntryLineDto(vatRate = "19", amount = "59.50")),
                                belegtext = "Corrected Barausgabe",
                                documentId = null,
                            ),
                    ),
            )

            val ids = loadTriple(originalId)

            // Reachability sanity: the reversal really is a CORRECTION_IN carrying the corrected
            // entry's 19 % rate and the inverted cash flow, produced by the real service.
            transaction {
                val reversal = repository.getEntryById(ids.reversal)!!
                assertEquals(KassenbuchEntryType.CORRECTION_IN, reversal.entryType)
                assertEquals(
                    KassenbuchEntryType.CASH_EXPENSE,
                    reversal.correctsHeader?.entryType,
                    "reversal must carry the corrects-link the export resolves GV_TYP through",
                )
                assertEquals("19", reversal.lineItems.single().ustSatz)
                assertEquals(0, BigDecimal("119.00").compareTo(reversal.lineItems.single().vorzBetrag))
            }

            val pairLineVat = linesVat(ids.original, ids.reversal)
            val pairBonUst = transactionsVat(ids.original, ids.reversal)

            // ---- Producer 1: lines_vat.csv (generateAvLineVats) ----
            val originalLineVat = pairLineVat.single { it.bonId == bonId(ids.original) }
            assertEquals(1, originalLineVat.ustSchluessel)
            assertEquals("-119.00", originalLineVat.posBrutto)
            assertEquals("-100.00", originalLineVat.posNetto)
            assertEquals("-19.00", originalLineVat.posUst)

            val reversalLineVat = pairLineVat.single { it.bonId == bonId(ids.reversal) }
            assertEquals(
                1,
                reversalLineVat.ustSchluessel,
                "C6: the reversal of a 19% Barausgabe is itself a 19% event, not key-5 nicht steuerbar",
            )
            assertEquals("119.00", reversalLineVat.posBrutto)
            assertEquals("100.00", reversalLineVat.posNetto)
            assertEquals("19.00", reversalLineVat.posUst)

            // ---- Producer 2: transactions_vat.csv (appendKassenbuchBonkopfUst) ----
            val originalBonUst = pairBonUst.single { it.bonId == bonId(ids.original) }
            assertEquals(1, originalBonUst.ustSchluessel)
            assertEquals("-119.00", originalBonUst.bonBrutto)
            assertEquals("-100.00", originalBonUst.bonNetto)
            assertEquals("-19.00", originalBonUst.bonUst)

            val reversalBonUst = pairBonUst.single { it.bonId == bonId(ids.reversal) }
            assertEquals(1, reversalBonUst.ustSchluessel)
            assertEquals("119.00", reversalBonUst.bonBrutto)
            assertEquals("100.00", reversalBonUst.bonNetto)
            assertEquals("19.00", reversalBonUst.bonUst)

            // ---- Producer 3: businesscases.csv (aggregateKassenbuchBusinessCases) ----
            // The reversal alone, aggregated on its own, must book at Auszahlung / key 1 / +19,00.
            val reversalGv = businessCases(ids.reversal).single()
            assertEquals(BusinessCaseTypes.AUSZAHLUNG, reversalGv.gvTyp)
            assertEquals(1, reversalGv.ustSchluessel)
            assertEquals("119.00", reversalGv.zUmsBrutto)
            assertEquals("100.00", reversalGv.zUmsNetto)
            assertEquals("19.00", reversalGv.zUst)

            // ---- The fiscal claim: the Storno pair nets to zero USt in every producer ----
            assertEquals("0.00", sumOf(pairLineVat.map { it.posUst }).toDecimalString())
            assertEquals("0.00", sumOf(pairBonUst.map { it.bonUst }).toDecimalString())
            // businesscases collapses the pair into ONE Auszahlung/key-1 aggregate declaring 0,00 in
            // every column. (It is emitted rather than suppressed because the emit guard compares
            // `brutto != BigDecimal.ZERO` by equals, so the scale-2 `0.00` is not equal to scale-0
            // `ZERO` — a pre-existing quirk, fiscally harmless here and arguably clearer: the GV_TYP
            // existed and netted out.) The load-bearing part is that Z_UST is 0,00 and there is NO
            // stray key-5 row carrying the reversal's brutto.
            val pairGv = businessCases(ids.original, ids.reversal)
            assertEquals(
                listOf(BusinessCaseTypes.AUSZAHLUNG to 1),
                pairGv.map { it.gvTyp to it.ustSchluessel },
                "a fully reversed Barausgabe must leave exactly one netted Auszahlung/key-1 aggregate",
            )
            assertEquals("0.00", pairGv.single().zUmsBrutto)
            assertEquals("0.00", pairGv.single().zUmsNetto)
            assertEquals("0.00", pairGv.single().zUst)

            // ---- The RE_ENTRY is an ordinary booking and was never affected ----
            val reEntryGv = businessCases(ids.reEntry).single()
            assertEquals(BusinessCaseTypes.AUSZAHLUNG, reEntryGv.gvTyp)
            assertEquals(1, reEntryGv.ustSchluessel)
            assertEquals("-59.50", reEntryGv.zUmsBrutto)
            assertEquals("-50.00", reEntryGv.zUmsNetto)
            assertEquals("-9.50", reEntryGv.zUst)
        }

    @Test
    @DisplayName("CORRECTION_OUT reversing a 19% CASH_SALE unwinds the Umsatzsteuer in all three VAT producers")
    fun correctionOutUnwindsSaleVat() =
        runBlocking {
            // Barverkauf of 119,00 EUR brutto @ 19 %. Reversing an INCOMING entry yields a
            // CORRECTION_OUT, so the mirror of C6 must be fixed too.
            val originalId = seedOriginal(KassenbuchEntryType.CASH_SALE, gross = "119.00", vatRate = "19")

            service.createCorrection(
                employeeId = emp,
                deviceId = dev,
                headerId = originalId,
                request =
                    KassenbuchDto.CorrectEntryRequestDto(
                        reason = "Wrong amount",
                        correctedEntry =
                            KassenbuchDto.CreateKassenbuchEntryRequestDto(
                                entryType = KassenbuchEntryType.CASH_SALE,
                                lines = listOf(KassenbuchDto.EntryLineDto(vatRate = "19", amount = "59.50")),
                                belegtext = "Corrected Barverkauf",
                                documentId = null,
                            ),
                    ),
            )

            val ids = loadTriple(originalId)

            transaction {
                val reversal = repository.getEntryById(ids.reversal)!!
                assertEquals(KassenbuchEntryType.CORRECTION_OUT, reversal.entryType)
                assertEquals(KassenbuchEntryType.CASH_SALE, reversal.correctsHeader?.entryType)
            }

            val reversalLineVat = linesVat(ids.reversal).single()
            assertEquals(1, reversalLineVat.ustSchluessel)
            assertEquals("-119.00", reversalLineVat.posBrutto)
            assertEquals("-100.00", reversalLineVat.posNetto)
            assertEquals("-19.00", reversalLineVat.posUst)

            val reversalBonUst = transactionsVat(ids.reversal).single()
            assertEquals(1, reversalBonUst.ustSchluessel)
            assertEquals("-119.00", reversalBonUst.bonBrutto)
            assertEquals("-100.00", reversalBonUst.bonNetto)
            assertEquals("-19.00", reversalBonUst.bonUst)

            val reversalGv = businessCases(ids.reversal).single()
            assertEquals(BusinessCaseTypes.UMSATZ, reversalGv.gvTyp)
            assertEquals(1, reversalGv.ustSchluessel)
            assertEquals("-119.00", reversalGv.zUmsBrutto)
            assertEquals("-100.00", reversalGv.zUmsNetto)
            assertEquals("-19.00", reversalGv.zUst)

            // As in the CASH_EXPENSE case: one netted Umsatz/key-1 aggregate declaring 0,00.
            val pairGv = businessCases(ids.original, ids.reversal)
            assertEquals(
                listOf(BusinessCaseTypes.UMSATZ to 1),
                pairGv.map { it.gvTyp to it.ustSchluessel },
                "a fully reversed Barverkauf must leave exactly one netted Umsatz/key-1 aggregate",
            )
            assertEquals("0.00", pairGv.single().zUmsBrutto)
            assertEquals("0.00", pairGv.single().zUmsNetto)
            assertEquals("0.00", pairGv.single().zUst)
        }

    @Test
    @DisplayName("VAT-free Privateinlage correction stays key 5 (no over-correction of C6)")
    fun vatFreeCorrectionStaysKeyFive() =
        runBlocking {
            // A Privateinlage carries no VAT. Its reversal must NOT acquire one: getGvTyp resolves
            // through the corrected type to `Privateinlage`, which the shared classifier pins to
            // the nicht-steuerbar class (Anhang C p. 58 „ohne USt-Bezug (ID 5)").
            val originalId = seedOriginal(KassenbuchEntryType.PRIVATE_DEPOSIT, gross = "200.00", vatRate = "0")

            service.createCorrection(
                employeeId = emp,
                deviceId = dev,
                headerId = originalId,
                request =
                    KassenbuchDto.CorrectEntryRequestDto(
                        reason = "Booked twice",
                        correctedEntry =
                            KassenbuchDto.CreateKassenbuchEntryRequestDto(
                                entryType = KassenbuchEntryType.PRIVATE_DEPOSIT,
                                lines = listOf(KassenbuchDto.EntryLineDto(vatRate = "0", amount = "100.00")),
                                belegtext = "Corrected Privateinlage",
                                documentId = null,
                            ),
                    ),
            )

            val ids = loadTriple(originalId)

            val gv = businessCases(ids.reversal).single()
            assertEquals(BusinessCaseTypes.PRIVATEINLAGE, gv.gvTyp)
            assertEquals(5, gv.ustSchluessel)
            assertEquals("-200.00", gv.zUmsBrutto)
            assertEquals("-200.00", gv.zUmsNetto)
            assertEquals("0.00", gv.zUst)
        }

    // -------------------------------------------------------------------------
    // Documented behaviour of the unlinked-CORRECTION_* legacy/anomaly path
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("CORRECTION_* with no corrects-link stays DifferenzSollIst / key 5 in both mappings")
    fun unlinkedCorrectionFallsBackConsistently() {
        // KassenbuchServiceImpl.createEntry does not reject a CORRECTION_* without a corrects
        // link (guardBalanceSnapshotEntry falls through), so a null corrected type is reachable
        // and must be handled DELIBERATELY. getGvTyp documents the fallback to DifferenzSollIst
        // and logs a warning; the shared classifier pins that GV_TYP to key 5 (round-7 G2 house
        // decision), the only key consistent with a DifferenzSollIst bucket. Closing the creation
        // gap itself is a separate carried finding (G5).
        assertEquals(
            BusinessCaseTypes.DIFFERENZ_SOLL_IST,
            KassenbuchDsfinvkMapping.getGvTyp(KassenbuchEntryType.CORRECTION_IN, null),
        )
        assertEquals(
            BusinessCaseTypes.DIFFERENZ_SOLL_IST,
            KassenbuchDsfinvkMapping.getGvTyp(KassenbuchEntryType.CORRECTION_OUT, null),
        )
        assertFalse(KassenbuchTaxClassifier.isRateGoverned(BusinessCaseTypes.DIFFERENZ_SOLL_IST))
        assertEquals(
            BigDecimal.ZERO,
            KassenbuchTaxClassifier.effectiveVatRate(
                BusinessCaseTypes.DIFFERENZ_SOLL_IST,
                BigDecimal("0.19"),
            ),
        )
    }

    @Test
    @DisplayName("the shared classifier resolves CORRECTION_* through the corrected type (C6, round-7)")
    fun classifierFollowsCorrectsLink() {
        // Round-7 replaced the entry-level `hasVatImplication` with a per-line
        // (GV_TYP, declared rate) classifier. getGvTyp still carries the correctsHeader
        // resolution, so a correction still lands in the corrected entry's container — the
        // C6 guarantee, now expressed once instead of in two drifting places.
        fun rateFor(
            entryType: KassenbuchEntryType,
            corrected: KassenbuchEntryType?,
        ) = KassenbuchTaxClassifier.effectiveVatRate(
            KassenbuchDsfinvkMapping.getGvTyp(entryType, corrected),
            BigDecimal("0.19"),
        )

        // Reversing a taxable Barausgabe/Barverkauf keeps the declared rate (unwinds the VAT).
        assertEquals(
            BigDecimal("0.19"),
            rateFor(KassenbuchEntryType.CORRECTION_IN, KassenbuchEntryType.CASH_EXPENSE),
        )
        assertEquals(
            BigDecimal("0.19"),
            rateFor(KassenbuchEntryType.CORRECTION_OUT, KassenbuchEntryType.CASH_SALE),
        )
        // Reversing a Privateinlage must NOT acquire VAT — Anhang C p. 58 „ohne USt-Bezug (ID 5)".
        assertEquals(
            BigDecimal.ZERO,
            rateFor(KassenbuchEntryType.CORRECTION_OUT, KassenbuchEntryType.PRIVATE_DEPOSIT),
        )
        // The corrected type is IGNORED for a non-correction entry type, exactly like getGvTyp.
        assertEquals(
            BigDecimal("0.19"),
            rateFor(KassenbuchEntryType.CASH_EXPENSE, KassenbuchEntryType.PRIVATE_DEPOSIT),
        )
    }

    @Test
    @DisplayName("round-7 E1: CASH_WITHDRAWAL is the SAME Auszahlung as CASH_EXPENSE, at the declared rate")
    fun cashWithdrawalBooksTheRateContainerLikeCashExpense() {
        // Anhang I p. 121: „Barausgabe: Büromaterial 50€ zu 19%" -> GV_TYP „Auszahlung", rate
        // container. `getGvTyp` maps CASH_EXPENSE and CASH_WITHDRAWAL to the same GV_TYP, so the
        // spec cannot tell our two entry types apart. The deleted `hasVatImplication` returned
        // true for one and false for the other, so the export booked two identical
        // Geschäftsvorfälle at two different USt keys.
        val expenseGv = KassenbuchDsfinvkMapping.getGvTyp(KassenbuchEntryType.CASH_EXPENSE)
        val withdrawalGv = KassenbuchDsfinvkMapping.getGvTyp(KassenbuchEntryType.CASH_WITHDRAWAL)
        assertEquals(BusinessCaseTypes.AUSZAHLUNG, expenseGv)
        assertEquals(expenseGv, withdrawalGv)
        assertEquals(
            KassenbuchTaxClassifier.effectiveVatRate(expenseGv, BigDecimal("0.19")),
            KassenbuchTaxClassifier.effectiveVatRate(withdrawalGv, BigDecimal("0.19")),
        )
        assertEquals(
            BigDecimal("0.19"),
            KassenbuchTaxClassifier.effectiveVatRate(withdrawalGv, BigDecimal("0.19")),
        )
    }

    @Test
    @DisplayName("round-7 C1: the nine spec-pinned GV_TYPs discard the declared rate")
    fun theNinePinnedGvTypsDiscardTheDeclaredRate() {
        // Anhang I p. 118-122 + Anhang C p. 54/56/57/58 + Tz. 3.2.6 p. 27. The declared rate is
        // "19" by CLIENT DEFAULT, so this set is what stands between the default UI path and a
        // false §12 Abs. 1 declaration in an immutable signed record.
        listOf(
            BusinessCaseTypes.MEHRZWECKGUTSCHEIN_KAUF,
            BusinessCaseTypes.MEHRZWECKGUTSCHEIN_EINLOESUNG,
            BusinessCaseTypes.TRINKGELD_AN,
            BusinessCaseTypes.FORDERUNGSENTSTEHUNG,
            BusinessCaseTypes.FORDERUNGSAUFLOESUNG,
            BusinessCaseTypes.PRIVATEINLAGE,
            BusinessCaseTypes.PRIVATENTNAHME,
            BusinessCaseTypes.GELDTRANSIT,
            BusinessCaseTypes.ANFANGSBESTAND,
        ).forEach { gvTyp ->
            assertFalse(KassenbuchTaxClassifier.isRateGoverned(gvTyp), "$gvTyp must discard the rate")
            assertEquals(
                BigDecimal.ZERO,
                KassenbuchTaxClassifier.effectiveVatRate(gvTyp, BigDecimal("0.19")),
                "$gvTyp at a declared 19 % must still resolve to the 0 % container",
            )
        }
        // …and everything else is RATE-governed. TrinkgeldAG is the decisive contrast to
        // TrinkgeldAN (p. 120: the same 50,00 € Bar tender splits by who keeps the tip), and
        // EinzweckgutscheinEinloesung to MehrzweckgutscheinEinloesung (p. 118).
        listOf(
            BusinessCaseTypes.UMSATZ,
            BusinessCaseTypes.AUSZAHLUNG,
            BusinessCaseTypes.PFAND,
            BusinessCaseTypes.PFAND_RUECKZAHLUNG,
            BusinessCaseTypes.TRINKGELD_AG,
            BusinessCaseTypes.EINZWECKGUTSCHEIN_KAUF,
            BusinessCaseTypes.EINZWECKGUTSCHEIN_EINLOESUNG,
        ).forEach { gvTyp ->
            assertTrue(KassenbuchTaxClassifier.isRateGoverned(gvTyp), "$gvTyp must follow the rate")
            assertEquals(
                BigDecimal("0.19"),
                KassenbuchTaxClassifier.effectiveVatRate(gvTyp, BigDecimal("0.19")),
            )
        }
    }
}

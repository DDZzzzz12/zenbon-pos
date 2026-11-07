package org.example.project.features.kassenbuch

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.document.DocumentDto
import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.enums.kassenbuch.CorrectionRole
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.core.domain.enums.kassenbuch.KassenbuchLineKind
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
import org.example.project.features.tse.data.service.TseVorgangSigner
import org.example.project.features.zBon.data.repository.ZBonRepositoryImpl
import org.example.project.infrastructure.errors.PosException
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
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@DisplayName("KassenbuchServiceImpl signed-entry and correction-guard behavior")
class KassenbuchSignedEntryServiceTest {
    private val DEV = "test-device"

    private lateinit var service: KassenbuchServiceImpl
    private lateinit var repository: KassenbuchRepositoryImpl
    private lateinit var shop: ShopInfo
    private lateinit var employee: Employee
    private lateinit var emp: String

    @BeforeEach
    fun setup() {
        TestDatabase.setup()
        repository = KassenbuchRepositoryImpl(testKassenbuchDocuments())

        // Real TseServiceFactory: with no global TSE config it falls back to DISABLED mode,
        // whose signTransaction returns null (no signing, no FAIL/QUEUE branch). The repo/client
        // collaborators are never touched in DISABLED mode, so noop proxies are safe.
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

        // Terminal so terminalRepository.findByDeviceId(DEV) resolves with a clientId.
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
        TestDatabase.teardown()
    }

    // -------------------------------------------------------------------------
    // Fakes
    // -------------------------------------------------------------------------

    /** Throwing proxy for collaborators that must NOT be called in these tests. */
    private inline fun <reified T : Any> noopProxy(): T {
        @Suppress("UNCHECKED_CAST")
        return Proxy.newProxyInstance(
            T::class.java.classLoader,
            arrayOf(T::class.java),
        ) { _, method, _ ->
            throw NotImplementedError("Stub: ${T::class.simpleName}.${method.name} should not be called in correction tests")
        } as T
    }

    /**
     * Config manager stub: TSE is DISABLED (mode=DISABLED, failureBehavior=QUEUE), which is the
     * scenario these correction tests run under (the real TseServiceFactory's signTransaction
     * returns null → resolver yields DISABLED). Only the two TSE accessors are answered; any other
     * method is a programming error in this harness.
     */
    private val disabledTseConfigManager: org.example.project.core.config.IShopConfigurationManager by lazy {
        @Suppress("UNCHECKED_CAST")
        Proxy.newProxyInstance(
            org.example.project.core.config.IShopConfigurationManager::class.java.classLoader,
            arrayOf(org.example.project.core.config.IShopConfigurationManager::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getTseMode" -> org.example.project.core.domain.enums.setup.TseMode.DISABLED
                // createEntry reads the shop's Beleg-age limit for its date guard.
                "getKassenbuchMaxBelegAgeDays" -> org.example.project.core.utils.KassenbuchBelegDateRule.DEFAULT_MAX_AGE_DAYS
                "getTseConfig" ->
                    org.example.project.core.domain.dto.tse.TseConfigDto.TseConfig(
                        timeAdminPin = "00000",
                    )
                // The money DTOs now carry the shop currency, so mappers resolve it on these paths.
                "getDefaultCurrency" -> "EUR"
                else -> throw NotImplementedError(
                    "Stub: IShopConfigurationManager.${method.name} should not be called in correction tests",
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

    /** PdfGenerationService fake: generateEigenbelegPdf creates a real Document, returns its summary. */
    private val fakePdfGenerationService: PdfGenerationService by lazy {
        @Suppress("UNCHECKED_CAST")
        Proxy.newProxyInstance(
            PdfGenerationService::class.java.classLoader,
            arrayOf(PdfGenerationService::class.java),
        ) { _, method, _ ->
            if (method.name == "generateEigenbelegPdf") {
                // Mirror the real impl: it persists a Document + maps the entity, so it MUST
                // run within a transaction. This guards against the caller forgetting dbQuery.
                org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
                    .currentOrNull()
                    ?: error("generateEigenbelegPdf must be called within a transaction")
                val id = newDocumentId("storno-eigenbeleg.pdf")
                DocumentDto.DocumentResponseDto(
                    id = id,
                    filename = "storno-eigenbeleg.pdf",
                    fileType = "INVOICE",
                    fileSize = 1024L,
                    uploadedAt = "2026-06-24T00:00:00",
                    uploadedBy = emp,
                    downloadUrl = "/download/$id",
                    formattedFileSize = "1.0 KB",
                    monthFolder = "2026-06",
                )
            } else {
                throw NotImplementedError("PdfGenerationService.${method.name} should not be called")
            }
        } as PdfGenerationService
    }

    /** DocumentManagementService fake: copyDocument creates a fresh Document row, returns its id. */
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

    private fun correctedEntryRequest(gross: String = "90.0") =
        KassenbuchDto.CreateKassenbuchEntryRequestDto(
            entryType = KassenbuchEntryType.CASH_SALE,
            lines = listOf(KassenbuchDto.EntryLineDto(vatRate = "19", amount = gross)),
            belegtext = "Corrected entry",
            documentId = null,
        )

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    fun `signed expense entry stores cash-flow signs, kinds, and canonical rates`() =
        runBlocking {
            // Round-7 D1: this fixture used to use GUTSCHEIN lines here. `getGvTyp`'s per-line
            // override is gated on the header GV_TYP being „Umsatz", so a GUTSCHEIN line inside a
            // Barausgabe was never a voucher redemption in the export — it was part of the
            // `Auszahlung`, and the kind carried no fiscal meaning at all. That combination is now
            // rejected at creation (see [gutscheinLineIsRejectedOutsideAnUmsatzVorgang]). PFAND is the
            // kind that IS meaningful on a supplier invoice (deposit on crates/bottles) and exercises
            // the same sign-correction and canonicalization paths.
            // F59: a 30.56 Barausgabe needs that much in the Ladenkasse (Kassenminus guard). The
            // deposit is booked first, so the expense is still the newest header asserted below.
            TestData.fundCashBook("30.56")
            val docId = newDocumentId("metro.pdf")
            service.createEntry(
                employee = emp,
                deviceId = DEV,
                request =
                    KassenbuchDto.CreateKassenbuchEntryRequestDto(
                        entryType = KassenbuchEntryType.CASH_EXPENSE,
                        lines =
                            listOf(
                                KassenbuchDto.EntryLineDto(vatRate = "19,00", amount = "23.78", belegtext = "Waren 19%"),
                                KassenbuchDto.EntryLineDto(vatRate = "7", amount = "131.78"),
                                KassenbuchDto.EntryLineDto(kind = KassenbuchLineKind.PFAND, vatRate = "19", amount = "-19.11"),
                                KassenbuchDto.EntryLineDto(kind = KassenbuchLineKind.PFAND, vatRate = "7", amount = "-105.89"),
                            ),
                        belegtext = "Metro Wareneinkauf",
                        documentId = docId,
                    ),
            )
            transaction {
                val header = KassenbuchHeader.all().orderBy(KassenbuchHeaderTable.id to SortOrder.DESC).first()
                assertEquals(0, java.math.BigDecimal("-30.56").compareTo(header.totalAmount))
                val lines = header.lineItems.sortedBy { it.lineNumber }
                assertEquals(
                    listOf("-23.78", "-131.78", "19.11", "105.89"),
                    lines.map { it.vorzBetrag.toPlainString() },
                )
                assertEquals("19", lines[0].ustSatz) // canonicalized from "19,00"
                assertEquals(
                    listOf(
                        KassenbuchLineKind.STANDARD,
                        KassenbuchLineKind.STANDARD,
                        KassenbuchLineKind.PFAND,
                        KassenbuchLineKind.PFAND,
                    ),
                    lines.map { it.kind },
                )
            }
        }

    @Test
    fun `gutschein line is rejected outside an Umsatz Vorgang (round-7 D1)`() =
        runBlocking {
            // The shared tax classifier is TOTAL — a GUTSCHEIN line inside a Barausgabe resolves to
            // the `Auszahlung` rule (declared rate) with no special-casing, because an undefined cell
            // on a SIGNING path is strictly worse than a determinate one. But the combination is
            // meaningless: Anhang I has no voucher-inside-payout example and Anhang C p. 54/55 defines
            // the Gutschein GV_TYPs as customer-facing. So the classifier stays total AND the cell is
            // made unreachable here (CLAUDE.md rule 8: guard at both levels; the client filters the
            // VAT/kind controls by entry type).
            val docId = newDocumentId("voucher-in-expense.pdf")
            val e =
                assertFailsWith<PosException> {
                    service.createEntry(
                        employee = emp,
                        deviceId = DEV,
                        request =
                            KassenbuchDto.CreateKassenbuchEntryRequestDto(
                                entryType = KassenbuchEntryType.CASH_EXPENSE,
                                lines =
                                    listOf(
                                        KassenbuchDto.EntryLineDto(vatRate = "19", amount = "100.0"),
                                        KassenbuchDto.EntryLineDto(
                                            kind = KassenbuchLineKind.GUTSCHEIN,
                                            vatRate = "19",
                                            amount = "-20.0",
                                        ),
                                    ),
                                belegtext = "Barausgabe mit Gutschein",
                                documentId = docId,
                            ),
                    )
                }
            assertTrue(e.message!!.contains("Gutschein"), "got: ${e.message}")

            // …and it is still accepted on the Umsatz family, where it IS a redemption.
            service.createEntry(
                employee = emp,
                deviceId = DEV,
                request =
                    KassenbuchDto.CreateKassenbuchEntryRequestDto(
                        entryType = KassenbuchEntryType.CASH_SALE,
                        lines =
                            listOf(
                                KassenbuchDto.EntryLineDto(vatRate = "19", amount = "100.0"),
                                KassenbuchDto.EntryLineDto(
                                    kind = KassenbuchLineKind.GUTSCHEIN,
                                    vatRate = "19",
                                    amount = "-20.0",
                                ),
                            ),
                        belegtext = "Verkauf mit Gutschein",
                        documentId = newDocumentId("voucher-in-sale.pdf"),
                    ),
            )
            Unit
        }

    @Test
    fun `correction round-trip preserves kinds and flips cash-flow signs via opposite entry type`() =
        runBlocking {
            // income entry with a negative Gutschein line
            val docId = newDocumentId("sale.pdf")
            service.createEntry(
                employee = emp,
                deviceId = DEV,
                request =
                    KassenbuchDto.CreateKassenbuchEntryRequestDto(
                        entryType = KassenbuchEntryType.CASH_SALE,
                        lines =
                            listOf(
                                KassenbuchDto.EntryLineDto(vatRate = "19", amount = "100.0"),
                                KassenbuchDto.EntryLineDto(kind = KassenbuchLineKind.GUTSCHEIN, vatRate = "19", amount = "-20.0"),
                            ),
                        belegtext = "Verkauf",
                        documentId = docId,
                    ),
            )
            val originalId =
                transaction {
                    KassenbuchHeader
                        .all()
                        .orderBy(KassenbuchHeaderTable.id to SortOrder.DESC)
                        .first()
                        .id.value
                }

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

            transaction {
                val reversal =
                    repository
                        .findCorrectionsFor(originalId)
                        .first { it.correctionRole == CorrectionRole.REVERSAL }
                assertEquals(KassenbuchEntryType.CORRECTION_OUT, reversal.entryType)
                val lines = reversal.lineItems.sortedBy { it.lineNumber }
                // cash-flow signs inverted line-by-line: +100/-20 -> -100/+20
                assertEquals(listOf("-100.00", "20.00"), lines.map { it.vorzBetrag.toPlainString() })
                assertEquals(
                    listOf(KassenbuchLineKind.STANDARD, KassenbuchLineKind.GUTSCHEIN),
                    lines.map { it.kind },
                )
            }
        }

    @Test
    fun `entries with NON_CASH_PAYMENT lines cannot be corrected and no Eigenbeleg is persisted`() =
        runBlocking {
            val docId = newDocumentId("zbon.pdf")
            val headerId =
                transaction {
                    val header =
                        repository.createEntry(
                            shop = shop,
                            employee = employee,
                            headerData =
                                KassenbuchDto.KassenbuchData(
                                    entryType = KassenbuchEntryType.CASH_SALE,
                                    belegNr = "2026-07-0400",
                                    vorzBetrag = "80.0",
                                    belegDatum = "08.07.2026",
                                    belegtext = "ZBon Nr. 5",
                                ),
                            lineItemsData =
                                listOf(
                                    KassenbuchDto.LineItemData(vorzBetrag = "100.0", ustSatz = "19"),
                                    KassenbuchDto.LineItemData(vorzBetrag = "-20.0", kind = KassenbuchLineKind.NON_CASH_PAYMENT),
                                ),
                        )
                    repository.linkDocument(header.id.value, docId)
                    header.id.value
                }
            val documentsBefore = transaction { Document.all().count() }

            val ex =
                assertFailsWith<PosException> {
                    service.createCorrection(
                        employeeId = emp,
                        deviceId = DEV,
                        headerId = headerId,
                        request =
                            KassenbuchDto.CorrectEntryRequestDto(
                                reason = "should fail",
                                correctedEntry = correctedEntryRequest(),
                            ),
                    )
                }
            assertTrue(ex.message!!.contains("cannot be corrected"))
            assertEquals(documentsBefore, transaction { Document.all().count() }, "no Storno-Eigenbeleg persisted")
            Unit
        }

    @Test
    fun `all-cash Z-Bon aggregate (no non-cash lines) cannot be corrected`() =
        runBlocking {
            val docId = newDocumentId("agg.pdf")
            val headerId =
                transaction {
                    val header =
                        repository.createEntry(
                            shop = shop,
                            employee = employee,
                            headerData =
                                KassenbuchDto.KassenbuchData(
                                    entryType = KassenbuchEntryType.CASH_SALE,
                                    belegNr = "2026-07-0500",
                                    vorzBetrag = "100.0",
                                    belegDatum = "09.07.2026",
                                    belegtext = "ZBon Nr. 7",
                                    isZbonAggregate = true,
                                ),
                            // all-cash day: only a STANDARD line, NO NON_CASH_PAYMENT line
                            lineItemsData = listOf(KassenbuchDto.LineItemData(vorzBetrag = "100.0", ustSatz = "19")),
                        )
                    repository.linkDocument(header.id.value, docId)
                    header.id.value
                }
            val documentsBefore = transaction { Document.all().count() }

            val ex =
                assertFailsWith<PosException> {
                    service.createCorrection(
                        employeeId = emp,
                        deviceId = DEV,
                        headerId = headerId,
                        request =
                            KassenbuchDto.CorrectEntryRequestDto(
                                reason = "should fail",
                                correctedEntry = correctedEntryRequest(),
                            ),
                    )
                }
            assertEquals(HttpStatusCode.UnprocessableEntity, ex.httpStatus)
            assertTrue(ex.message!!.contains("Z-Bon aggregate"))
            assertEquals(documentsBefore, transaction { Document.all().count() }, "no Eigenbeleg persisted")
            Unit
        }

    @Test
    fun `getRecentEntries returns the most recently created entries first`() =
        runBlocking {
            val firstDocId = newDocumentId("first.pdf")
            val secondDocId = newDocumentId("second.pdf")

            service.createEntry(
                employee = emp,
                deviceId = DEV,
                request =
                    KassenbuchDto.CreateKassenbuchEntryRequestDto(
                        entryType = KassenbuchEntryType.CASH_SALE,
                        lines = listOf(KassenbuchDto.EntryLineDto(vatRate = "19", amount = "50.0")),
                        belegtext = "First entry",
                        documentId = firstDocId,
                    ),
            )

            service.createEntry(
                employee = emp,
                deviceId = DEV,
                request =
                    KassenbuchDto.CreateKassenbuchEntryRequestDto(
                        entryType = KassenbuchEntryType.CASH_SALE,
                        lines = listOf(KassenbuchDto.EntryLineDto(vatRate = "19", amount = "75.0")),
                        belegtext = "Second entry",
                        documentId = secondDocId,
                    ),
            )

            val result = service.getRecentEntries(2)

            assertEquals(2, result.size)
            assertEquals("Second entry", result.first().belegtext)
            assertEquals("First entry", result.last().belegtext)
        }
}

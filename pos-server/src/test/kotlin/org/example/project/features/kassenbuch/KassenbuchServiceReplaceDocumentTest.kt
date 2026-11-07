package org.example.project.features.kassenbuch

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.core.domain.enums.tse.TseSigningStatus
import org.example.project.core.error.PosErrorCode
import org.example.project.features.document.domain.models.Document
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.kassenbuch.data.repository.CashBookRepositoryImpl
import org.example.project.features.kassenbuch.data.repository.KassenbuchRepositoryImpl
import org.example.project.features.kassenbuch.data.service.KassenbuchServiceImpl
import org.example.project.features.kassenbuch.domain.models.KassenbuchHeader
import org.example.project.features.setup.domain.models.ShopInfo
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@DisplayName("KassenbuchServiceImpl.replaceDocument")
class KassenbuchServiceReplaceDocumentTest {
    private lateinit var service: KassenbuchServiceImpl
    private lateinit var repository: KassenbuchRepositoryImpl
    private lateinit var shop: ShopInfo
    private lateinit var employee: Employee

    @BeforeEach
    fun setup() {
        TestDatabase.setup()
        repository = KassenbuchRepositoryImpl(testKassenbuchDocuments())

        // Proxy out the unused collaborators — replaceDocument only touches the repository.
        service =
            KassenbuchServiceImpl(
                kassenbuchRepository = repository,
                zBonRepository = ZBonRepositoryImpl(),
                employeeRepository = noopProxy(),
                shopInfoRepository = noopProxy(),
                tseServiceFactory =
                    org.example.project.features.tse.domain.factory.TseServiceFactory(
                        tseTransactionRepository = noopProxy(),
                        tseSignClient = noopProxy(),
                        signer = TseVorgangSigner(noopProxy(), noopProxy(), noopProxy()),
                    ),
                tseSigningQueueService = noopProxy(),
                terminalRepository = noopProxy(),
                documents = testKassenbuchDocuments(pdfGenerationService = noopProxy(), documentManagementService = noopProxy()),
                configManager = noopProxy(),
            )

        shop = TestData.createShopInfo()
        employee = TestData.createAdmin()
    }

    @AfterEach
    fun teardown() {
        TestDatabase.teardown()
    }

    private inline fun <reified T : Any> noopProxy(): T {
        @Suppress("UNCHECKED_CAST")
        return Proxy.newProxyInstance(
            T::class.java.classLoader,
            arrayOf(T::class.java),
        ) { _, method, _ ->
            // The money DTOs now carry the shop currency, so mappers resolve it on these paths.
            if (method.name == "getDefaultCurrency") return@newProxyInstance "EUR"
            throw NotImplementedError("Stub: ${T::class.simpleName}.${method.name} should not be called in replaceDocument tests")
        } as T
    }

    private fun createHeader(
        isLocked: Boolean = false,
        linked: Document? = null,
    ): KassenbuchHeader =
        transaction {
            KassenbuchHeader.new {
                this.shop = this@KassenbuchServiceReplaceDocumentTest.shop
                this.cashBook =
                    CashBookRepositoryImpl().fiscalBookFor(this@KassenbuchServiceReplaceDocumentTest.shop)
                this.employee = this@KassenbuchServiceReplaceDocumentTest.employee
                this.entryType = KassenbuchEntryType.CASH_SALE
                this.belegNr = "2026-04-0001"
                this.belegDatum = "13.04.2026"
                this.belegtext = "Test entry"
                this.waehrung = "EUR"
                this.bu = null
                this.gegenkonto = null
                this.kost1 = null
                this.kost2 = null
                this.nachricht = null
                this.totalAmount = BigDecimal("10.0000")
                this.currentBalance = BigDecimal("10.0000")
                this.tseSigningStatus = TseSigningStatus.SIGNED
                this.linkedDocumentId = linked?.id?.value
                this.isLocked = isLocked
                this.createdAt = currentTimeStamp()
            }
        }

    private var docCounter = 0

    private fun createDocument(filename: String = "doc-${++docCounter}.pdf"): Document =
        transaction {
            Document.new {
                this.filename = filename
                this.fileType = "INVOICE"
                this.filePath = "/tmp/test/$filename"
                this.fileSize = 1024L
                this.uploadedAt = currentTimeStamp()
                this.uploadedBy = employee
                this.isActive = true
                this.contentHash = "hash-$filename"
                this.mediaType = "application/pdf"
            }
        }

    @Test
    fun `replaceDocument swaps linked document and keeps old document row`() =
        runBlocking {
            val oldDoc = createDocument("old.pdf")
            val newDoc = createDocument("new.pdf")
            val header = createHeader(linked = oldDoc)

            val detail =
                service.replaceDocument(
                    employee.id.value.toString(),
                    "test-device",
                    header.id.value,
                    newDoc.id.value,
                )

            assertEquals(header.id.value, detail.headerId)

            transaction {
                val reloaded = KassenbuchHeader.findById(header.id.value)
                assertNotNull(reloaded)
                assertEquals(newDoc.id.value, reloaded.linkedDocumentId)

                val oldReloaded = Document.findById(oldDoc.id.value)
                assertNotNull(oldReloaded, "Old document row must still exist after replace")
                assertEquals(oldDoc.id.value, oldReloaded.id.value)
            }
        }

    @Test
    fun `replaceDocument rejects when header is locked`() =
        runBlocking {
            val oldDoc = createDocument("old.pdf")
            val newDoc = createDocument("new.pdf")
            val header = createHeader(isLocked = true, linked = oldDoc)

            val ex =
                runCatching {
                    service.replaceDocument(
                        employee.id.value.toString(),
                        "test-device",
                        header.id.value,
                        newDoc.id.value,
                    )
                }.exceptionOrNull()
            assertNotNull(ex, "Expected PosException but call succeeded")
            assertTrue(ex is PosException, "Expected PosException, got ${ex::class.simpleName}")
            assertEquals(PosErrorCode.VALIDATION_FAILED, ex.code)

            // Header is unchanged
            transaction {
                val reloaded = KassenbuchHeader.findById(header.id.value)
                assertNotNull(reloaded)
                assertEquals(oldDoc.id.value, reloaded.linkedDocumentId)
            }
        }

    @Test
    fun `replaceDocument returns NOT_FOUND for unknown header`() =
        runBlocking {
            val newDoc = createDocument("new.pdf")

            val ex =
                runCatching {
                    service.replaceDocument(
                        employee.id.value.toString(),
                        "test-device",
                        999999L,
                        newDoc.id.value,
                    )
                }.exceptionOrNull()
            assertNotNull(ex, "Expected PosException but call succeeded")
            assertTrue(ex is PosException, "Expected PosException, got ${ex::class.simpleName}")
            assertEquals(PosErrorCode.RESOURCE_NOT_FOUND, ex.code)
        }

    @Test
    fun `replaceDocument rejects when new document is already linked to a different header`() =
        runBlocking {
            val docA = createDocument("a.pdf")
            val docB = createDocument("b.pdf")
            val headerA = createHeader(linked = docA)
            val headerB =
                transaction {
                    KassenbuchHeader.new {
                        this.shop = this@KassenbuchServiceReplaceDocumentTest.shop
                        this.cashBook =
                            CashBookRepositoryImpl()
                                .fiscalBookFor(this@KassenbuchServiceReplaceDocumentTest.shop)
                        this.employee = this@KassenbuchServiceReplaceDocumentTest.employee
                        this.entryType = KassenbuchEntryType.CASH_SALE
                        this.belegNr = "2026-04-0002"
                        this.belegDatum = "13.04.2026"
                        this.belegtext = "Second entry"
                        this.waehrung = "EUR"
                        this.bu = null
                        this.gegenkonto = null
                        this.kost1 = null
                        this.kost2 = null
                        this.nachricht = null
                        this.totalAmount = BigDecimal("10.0000")
                        this.currentBalance = BigDecimal("10.0000")
                        this.tseSigningStatus = TseSigningStatus.SIGNED
                        this.linkedDocumentId = docB.id.value
                        this.isLocked = false
                        this.createdAt = currentTimeStamp()
                    }
                }

            val ex =
                runCatching {
                    service.replaceDocument(
                        employee.id.value.toString(),
                        "test-device",
                        headerA.id.value,
                        docB.id.value,
                    )
                }.exceptionOrNull()
            assertNotNull(ex, "Expected PosException but call succeeded")
            assertTrue(ex is PosException, "Expected PosException, got ${ex::class.simpleName}")
            assertEquals(PosErrorCode.VALIDATION_FAILED, ex.code)

            transaction {
                assertEquals(
                    docA.id.value,
                    KassenbuchHeader
                        .findById(headerA.id.value)
                        ?.linkedDocumentId,
                )
                assertEquals(
                    docB.id.value,
                    KassenbuchHeader
                        .findById(headerB.id.value)
                        ?.linkedDocumentId,
                )
            }
        }

    @Test
    fun `replaceDocument returns NOT_FOUND for unknown document`() =
        runBlocking {
            val oldDoc = createDocument("old.pdf")
            val header = createHeader(linked = oldDoc)

            val ex =
                runCatching {
                    service.replaceDocument(
                        employee.id.value.toString(),
                        "test-device",
                        header.id.value,
                        999999L,
                    )
                }.exceptionOrNull()
            assertNotNull(ex, "Expected PosException but call succeeded")
            assertTrue(ex is PosException, "Expected PosException, got ${ex::class.simpleName}")
            assertEquals(PosErrorCode.RESOURCE_NOT_FOUND, ex.code)
        }
}

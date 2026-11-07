package org.example.project.features.kassenbuch

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import org.example.project.core.config.IShopConfigurationManager
import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.features.document.domain.service.DocumentManagementService
import org.example.project.features.document.domain.service.PdfGenerationService
import org.example.project.features.employee.data.repository.EmployeeRepositoryImpl
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.kassenbuch.data.repository.CashBookRepositoryImpl
import org.example.project.features.kassenbuch.data.repository.KassenbuchRepositoryImpl
import org.example.project.features.kassenbuch.data.service.KassenbuchServiceImpl
import org.example.project.features.kassenbuch.domain.models.KassenbuchHeader
import org.example.project.features.setup.data.repository.ShopInfoRepositoryImpl
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.terminal.data.repository.TerminalRepositoryImpl
import org.example.project.features.tse.data.service.TseVorgangSigner
import org.example.project.features.zBon.data.repository.ZBonRepositoryImpl
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.example.project.testutils.testKassenbuchDocuments
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import kotlin.test.assertEquals

/**
 * FO-43: the Kassenbuch list is in date order, newest first.
 *
 * `getEntryHistory` re-sorted the repository's `created_at DESC` result by Beleg-Nr, but Beleg-Nr
 * repeat per cash book (each book numbers from its own counter), so across books the list came out in
 * counter order — not date order — and a Tresor entry from this morning sat below a Ladenkasse entry
 * from last week. The ordering is listing only: the DATEV/DSFinV-K exports read the repository
 * directly and are unaffected.
 */
@DisplayName("Kassenbuch entry history — date order across cash books (FO-43)")
class KassenbuchEntryHistoryOrderTest {
    private lateinit var service: KassenbuchServiceImpl
    private lateinit var shop: ShopInfo
    private lateinit var employee: Employee

    @BeforeEach
    fun setup() {
        TestDatabase.setup()
        service =
            KassenbuchServiceImpl(
                kassenbuchRepository = KassenbuchRepositoryImpl(testKassenbuchDocuments()),
                zBonRepository = ZBonRepositoryImpl(),
                employeeRepository = EmployeeRepositoryImpl(),
                shopInfoRepository = ShopInfoRepositoryImpl(),
                tseServiceFactory =
                    org.example.project.features.tse.domain.factory.TseServiceFactory(
                        tseTransactionRepository = stub(),
                        tseSignClient = stub(),
                        signer = TseVorgangSigner(stub(), stub(), stub()),
                    ),
                tseSigningQueueService = stub(),
                terminalRepository = TerminalRepositoryImpl(),
                documents =
                    testKassenbuchDocuments(
                        pdfGenerationService = stub<PdfGenerationService>(),
                        documentManagementService = stub<DocumentManagementService>(),
                    ),
                configManager = configManager,
            )
        shop = TestData.createShopInfo()
        employee = TestData.createAdmin()
    }

    @AfterEach
    fun teardown() = TestDatabase.teardown()

    @Test
    fun `entries of two books come back newest first although their Beleg-Nr interleave`() {
        val tresor = transaction { CashBookRepositoryImpl().create(shop, "Tresor", isFiscal = false, datevAccount = "1010") }

        // Booking order (created_at), with each book's own Beleg-Nr counter: the newest entry has the
        // SMALLEST number, so a Beleg-Nr sort cannot give date order.
        seed("ladenkasse-0005", belegNr = "2026-10-0005", cashBookId = null, createdAt = at(1, 10))
        seed("tresor-0001", belegNr = "2026-10-0001", cashBookId = tresor.id.value, createdAt = at(1, 11))
        seed("ladenkasse-0006", belegNr = "2026-10-0006", cashBookId = null, createdAt = at(2, 8))
        seed("tresor-0002", belegNr = "2026-10-0002", cashBookId = tresor.id.value, createdAt = at(2, 9))

        val history = runBlocking { service.getEntryHistory("2026-10-01", "2026-10-02", unlinkedOnly = false, cashBookId = null) }

        assertEquals(
            listOf("tresor-0002", "ladenkasse-0006", "tresor-0001", "ladenkasse-0005"),
            history.map { it.belegtext },
        )
    }

    @Test
    fun `entries sharing one timestamp come back newest id first`() {
        seed("first", belegNr = "2026-10-0001", cashBookId = null, createdAt = at(1, 10))
        seed("second", belegNr = "2026-10-0002", cashBookId = null, createdAt = at(1, 10))
        seed("third", belegNr = "2026-10-0003", cashBookId = null, createdAt = at(1, 10))

        val history = runBlocking { service.getEntryHistory("2026-10-01", "2026-10-01", unlinkedOnly = false, cashBookId = null) }

        assertEquals(listOf("third", "second", "first"), history.map { it.belegtext })
    }

    // ---- fixtures ------------------------------------------------------------

    private fun at(
        day: Int,
        hour: Int,
    ) = LocalDateTime(2026, 10, day, hour, 0)

    private fun seed(
        label: String,
        belegNr: String,
        cashBookId: Long?,
        createdAt: LocalDateTime,
    ) {
        transaction {
            val header =
                KassenbuchRepositoryImpl(testKassenbuchDocuments()).createEntry(
                    shop = shop,
                    employee = employee,
                    headerData =
                        KassenbuchDto.KassenbuchData(
                            entryType = KassenbuchEntryType.CASH_SALE,
                            belegNr = belegNr,
                            vorzBetrag = "10.00",
                            belegDatum = "01.10.2026",
                            belegtext = label,
                            cashBookId = cashBookId,
                        ),
                    lineItemsData = listOf(KassenbuchDto.LineItemData(vorzBetrag = "10.00")),
                )
            KassenbuchHeader.findById(header.id.value)!!.createdAt = createdAt
        }
    }

    private val configManager: IShopConfigurationManager by lazy {
        Proxy.newProxyInstance(
            IShopConfigurationManager::class.java.classLoader,
            arrayOf(IShopConfigurationManager::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getDefaultCurrency" -> "EUR"
                else -> throw NotImplementedError("IShopConfigurationManager.${method.name} should not be called")
            }
        } as IShopConfigurationManager
    }

    private inline fun <reified T : Any> stub(): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            throw NotImplementedError("${T::class.simpleName}.${method.name} should not be called")
        } as T
}

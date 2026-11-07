package org.example.project.features.kassenbuch

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.core.domain.dto.kassenbuch.CashBookDto
import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.enums.kassenbuch.CashBookCloseInterval
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.core.error.PosErrorCode
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.kassenbuch.data.repository.CashBookRepositoryImpl
import org.example.project.features.kassenbuch.data.repository.KassenbuchRepositoryImpl
import org.example.project.features.kassenbuch.data.service.CashBookServiceImpl
import org.example.project.features.setup.data.repository.ShopInfoRepositoryImpl
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.terminal.data.repository.TerminalRepositoryImpl
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.example.project.testutils.testKassenbuchDocuments
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Task 26 — cash-book CRUD. Covers [CashBookServiceImpl] directly; the route-mounting/permission
 * proof lives in [org.example.project.routes.CashBookCloseRoutesTest].
 */
class CashBookServiceTest {
    private lateinit var books: CashBookRepositoryImpl
    private lateinit var kassenbuch: KassenbuchRepositoryImpl
    private lateinit var service: CashBookServiceImpl
    private lateinit var shop: ShopInfo

    @BeforeEach
    fun setup() {
        TestDatabase.setup()
        books = CashBookRepositoryImpl()
        kassenbuch = KassenbuchRepositoryImpl(testKassenbuchDocuments())
        service =
            CashBookServiceImpl(
                cashBookRepository = books,
                kassenbuchRepository = kassenbuch,
                shopInfoRepository = ShopInfoRepositoryImpl(),
                // The real manager: with no DEFAULT_CURRENCY row it falls back to "EUR", which is
                // exactly what the response should then carry.
                configManager = ShopConfigurationManager,
                terminalRepository = TerminalRepositoryImpl(),
            )
        shop = TestData.createShopInfo()
        TestData.createAdmin()
    }

    @AfterEach
    fun teardown() = TestDatabase.teardown()

    private fun deposit(
        bookId: Long,
        amount: String,
    ) {
        transaction {
            val employee = Employee.all().first()
            kassenbuch.createEntry(
                shop = shop,
                employee = employee,
                headerData =
                    KassenbuchDto.KassenbuchData(
                        entryType = KassenbuchEntryType.CASH_DEPOSIT,
                        belegNr = kassenbuch.nextBelegNrBlocking(bookId),
                        vorzBetrag = amount,
                        belegDatum = "01.08.2026",
                        belegtext = "Test",
                        cashBookId = bookId,
                    ),
                lineItemsData = listOf(KassenbuchDto.LineItemData(vorzBetrag = amount, ustSatz = null)),
            )
        }
    }

    private fun updateRequest(
        name: String = "Tresor",
        datevAccount: String = "1010",
        isActive: Boolean = true,
        closeInterval: CashBookCloseInterval = CashBookCloseInterval.MANUAL,
        sortOrder: Int = 0,
    ) = CashBookDto.UpdateRequestDto(
        name = name,
        datevAccount = datevAccount,
        closeInterval = closeInterval,
        isActive = isActive,
        sortOrder = sortOrder,
    )

    // -------------------------------------------------------------------------
    // create
    // -------------------------------------------------------------------------

    @Test
    fun `create always produces a non-fiscal book`() {
        // CreateRequestDto structurally has no isFiscal field to get wrong — this proves the
        // service hardcodes isFiscal = false rather than defaulting it some other way. If the
        // service ever started honouring a caller-supplied fiscal flag (or defaulted to true),
        // this assertion would fail.
        val response =
            runBlocking {
                service.create(
                    CashBookDto.CreateRequestDto(name = "Tresor", datevAccount = "1010"),
                )
            }

        assertFalse(response.isFiscal, "a book created over HTTP must never be fiscal")
        transaction {
            val stored = books.findById(response.id) ?: error("book not persisted")
            assertFalse(stored.isFiscal)
        }
    }

    // -------------------------------------------------------------------------
    // duplicate names (checklist 21.1)
    // -------------------------------------------------------------------------

    private fun assertNameTaken(block: suspend () -> Unit) {
        val refusal = assertFailsWith<PosException> { runBlocking { block() } }
        assertEquals(PosErrorCode.CASH_BOOK_NAME_ALREADY_TAKEN, refusal.code)
        assertEquals(HttpStatusCode.Conflict, refusal.httpStatus)
    }

    @Test
    fun `creating a second book with an existing name is refused with 409`() {
        runBlocking { service.create(CashBookDto.CreateRequestDto(name = "Tresor", datevAccount = "1010")) }

        // The live defect: the second POST answered 201 and produced two books called "Tresor".
        assertNameTaken { service.create(CashBookDto.CreateRequestDto(name = "Tresor", datevAccount = "1010")) }
        assertEquals(1, transaction { books.findByShop(shop, includeInactive = true).count { it.name == "Tresor" } })
    }

    @Test
    fun `the name comparison ignores case and surrounding blanks`() {
        runBlocking { service.create(CashBookDto.CreateRequestDto(name = "Tresor", datevAccount = "1010")) }

        assertNameTaken { service.create(CashBookDto.CreateRequestDto(name = "  tresor ", datevAccount = "1020")) }
    }

    @Test
    fun `the fiscal Ladenkasse's name cannot be reused`() {
        transaction { books.fiscalBookFor(shop) }

        assertNameTaken { service.create(CashBookDto.CreateRequestDto(name = "LADENKASSE", datevAccount = "1010")) }
    }

    @Test
    fun `a deactivated book still holds its name`() {
        val old = transaction { books.create(shop, "Nebenkasse", false, "1010").id.value }
        runBlocking { service.update(old, updateRequest(name = "Nebenkasse", isActive = false)) }

        assertNameTaken { service.create(CashBookDto.CreateRequestDto(name = "Nebenkasse", datevAccount = "1020")) }
    }

    @Test
    fun `renaming onto another book's name is refused`() {
        transaction { books.create(shop, "Tresor", false, "1010") }
        val safe = transaction { books.create(shop, "Safe", false, "1020").id.value }

        assertNameTaken { service.update(safe, updateRequest(name = "Tresor", datevAccount = "1020")) }
    }

    @Test
    fun `a book may keep or re-case its own name`() {
        val tresor = transaction { books.create(shop, "Tresor", false, "1010").id.value }

        val response = runBlocking { service.update(tresor, updateRequest(name = " TRESOR ")) }

        assertEquals("TRESOR", response.name, "the stored name is trimmed")
    }

    // -------------------------------------------------------------------------
    // update — deactivation guards
    // -------------------------------------------------------------------------

    @Test
    fun `deactivating a book with a non-zero balance is refused`() {
        val tresor = transaction { books.create(shop, "Tresor", false, "1010").id.value }
        deposit(tresor, "50.00")

        // If the guard were missing, this update would succeed and silently strand the 50.00
        // balance in a book no longer visible to daily operation.
        assertFailsWith<PosException> {
            runBlocking { service.update(tresor, updateRequest(isActive = false)) }
        }
        Unit
    }

    @Test
    fun `deactivating an empty book succeeds`() {
        val tresor = transaction { books.create(shop, "Tresor", false, "1010").id.value }

        val response = runBlocking { service.update(tresor, updateRequest(isActive = false)) }

        assertFalse(response.isActive, "a zero-balance non-fiscal book must be deactivatable")
    }

    @Test
    fun `the fiscal book cannot be deactivated`() {
        val kasse = transaction { books.fiscalBookFor(shop).id.value }

        // Zero balance on purpose — the guard must trip on isFiscal alone, not on balance. If the
        // service only checked balance, a fresh (zero-balance) fiscal book would wrongly pass.
        assertFailsWith<PosException> {
            runBlocking { service.update(kasse, updateRequest(name = "Ladenkasse", datevAccount = "1000", isActive = false)) }
        }
        Unit
    }

    // -------------------------------------------------------------------------
    // list
    // -------------------------------------------------------------------------

    @Test
    fun `list returns each book with its own balance`() {
        val tresor = transaction { books.create(shop, "Tresor", false, "1010").id.value }
        val safe = transaction { books.create(shop, "Safe", false, "1020").id.value }
        deposit(tresor, "50.00")
        deposit(safe, "500.00")

        val result = runBlocking { service.list() }

        val tresorDto = result.first { it.id == tresor }
        val safeDto = result.first { it.id == safe }
        assertEquals(0, BigDecimal("50.00").compareTo(BigDecimal(tresorDto.currentBalance)))
        assertEquals(0, BigDecimal("500.00").compareTo(BigDecimal(safeDto.currentBalance)))
        assertTrue(tresorDto.currentBalance != safeDto.currentBalance, "each book's balance must be independent")
    }

    @Test
    fun `every book carries the shop currency even with no entries`() {
        // The point of the field: a book with nothing booked in the client's viewed period still
        // has to render its balance in a unit, and the client must not have to guess one from an
        // unrelated payload.
        transaction { books.create(shop, "Tresor", false, "1010") }

        val result = runBlocking { service.list() }

        assertTrue(result.isNotEmpty())
        assertTrue(result.all { it.currency == "EUR" }, "currency must come from the shop config, not the entries")
    }

    @Test
    fun `a deactivated book is listed only when includeInactive is asked for`() {
        val tresor = transaction { books.create(shop, "Tresor", false, "1010").id.value }
        runBlocking { service.update(tresor, updateRequest(name = "Tresor", datevAccount = "1010", isActive = false)) }

        assertTrue(runBlocking { service.list() }.none { it.id == tresor }, "daily operation never offers a deactivated book")
        val all = runBlocking { service.list(includeInactive = true) }
        assertFalse(all.first { it.id == tresor }.isActive, "the management dialog needs it to re-activate it")
    }
}

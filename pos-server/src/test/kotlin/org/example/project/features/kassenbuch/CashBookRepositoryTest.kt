package org.example.project.features.kassenbuch

import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.kassenbuch.data.repository.CashBookRepositoryImpl
import org.example.project.features.kassenbuch.data.repository.KassenbuchRepositoryImpl
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.example.project.testutils.testKassenbuchDocuments
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CashBookRepositoryTest {
    private lateinit var repository: CashBookRepositoryImpl
    private lateinit var kassenbuchRepository: KassenbuchRepositoryImpl
    private lateinit var shop: ShopInfo
    private lateinit var employee: Employee

    @BeforeEach
    fun setup() {
        TestDatabase.setup()
        repository = CashBookRepositoryImpl()
        kassenbuchRepository = KassenbuchRepositoryImpl(testKassenbuchDocuments())
        shop = TestData.createShopInfo()
        employee = TestData.createAdmin()
    }

    @AfterEach
    fun teardown() = TestDatabase.teardown()

    // Verbatim copy of the helper shape from CashBookScopedBalanceTest — reuse is not possible
    // since it's private in that class.
    private fun deposit(
        bookId: Long,
        amount: String,
    ) = transaction {
        kassenbuchRepository.createEntry(
            shop = shop,
            employee = employee,
            headerData =
                KassenbuchDto.KassenbuchData(
                    entryType = KassenbuchEntryType.CASH_DEPOSIT,
                    belegNr = kassenbuchRepository.nextBelegNrBlocking(bookId),
                    vorzBetrag = amount,
                    belegDatum = "01.08.2026",
                    belegtext = "Test",
                    cashBookId = bookId,
                ),
            lineItemsData = listOf(KassenbuchDto.LineItemData(vorzBetrag = amount, ustSatz = null)),
            tseTransaction = null,
        )
    }

    @Test
    fun `fiscalBookFor creates a Ladenkasse on first call`() {
        val book = transaction { repository.fiscalBookFor(shop) }

        assertEquals("Ladenkasse", book.name)
        assertTrue(book.isFiscal)
        assertEquals("1000", book.datevAccount)
    }

    @Test
    fun `fiscalBookFor is idempotent`() {
        val first = transaction { repository.fiscalBookFor(shop).id.value }
        val second = transaction { repository.fiscalBookFor(shop).id.value }

        assertEquals(first, second)
    }

    @Test
    fun `create refuses a second fiscal book for the same shop`() {
        transaction { repository.fiscalBookFor(shop) }

        assertFailsWith<IllegalStateException> {
            transaction {
                repository.create(shop, name = "Zweitkasse", isFiscal = true, datevAccount = "1001")
            }
        }
        Unit
    }

    @Test
    fun `create allows multiple non-fiscal books`() {
        transaction { repository.fiscalBookFor(shop) }
        val tresor = transaction { repository.create(shop, "Tresor", isFiscal = false, datevAccount = "1010") }
        val porto = transaction { repository.create(shop, "Portokasse", isFiscal = false, datevAccount = "1011") }

        assertTrue(tresor.id.value != porto.id.value)
    }

    @Test
    fun `isFiscal cannot flip once the book has entries`() {
        val book = transaction { repository.fiscalBookFor(shop) }
        deposit(book.id.value, "10.00")

        assertFailsWith<IllegalStateException> {
            transaction { repository.setFiscal(book.id.value, false) }
        }
        Unit
    }

    @Test
    fun `isFiscal may flip while the book is empty`() {
        val tresor = transaction { repository.create(shop, "Tresor", false, "1010") }
        transaction { repository.setFiscal(tresor.id.value, true) }

        assertTrue(transaction { repository.findById(tresor.id.value)!!.isFiscal })
    }
}

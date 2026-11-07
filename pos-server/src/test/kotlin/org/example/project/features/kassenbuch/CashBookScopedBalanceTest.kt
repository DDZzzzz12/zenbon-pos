package org.example.project.features.kassenbuch

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.kassenbuch.data.repository.CashBookRepositoryImpl
import org.example.project.features.kassenbuch.data.repository.KassenbuchRepositoryImpl
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.example.project.testutils.testKassenbuchDocuments
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals

class CashBookScopedBalanceTest {
    private lateinit var repo: KassenbuchRepositoryImpl
    private lateinit var books: CashBookRepositoryImpl
    private lateinit var shop: ShopInfo
    private lateinit var employee: Employee

    @BeforeEach
    fun setup() {
        TestDatabase.setup()
        repo = KassenbuchRepositoryImpl(testKassenbuchDocuments())
        books = CashBookRepositoryImpl()
        shop = TestData.createShopInfo()
        employee = TestData.createAdmin()
    }

    @AfterEach
    fun teardown() = TestDatabase.teardown()

    private fun deposit(
        bookId: Long,
        amount: String,
    ) = transaction {
        repo.createEntry(
            shop = shop,
            employee = employee,
            headerData =
                KassenbuchDto.KassenbuchData(
                    entryType = KassenbuchEntryType.CASH_DEPOSIT,
                    belegNr = repo.nextBelegNrBlocking(bookId),
                    vorzBetrag = amount,
                    belegDatum = "01.08.2026",
                    belegtext = "Test",
                    cashBookId = bookId,
                ),
            // VERIFIED against the real declaration:
            //   LineItemData(vorzBetrag, ustSatz, belegtext, kostmenge, skonto, kind, paymentTypeId)
            // Note `ustSatz` — lowercase u — and that `kind` defaults to STANDARD. There is
            // no KassenbuchLineKind.CASH; the four values are STANDARD, PFAND, GUTSCHEIN,
            // NON_CASH_PAYMENT. CASH_DEPOSIT has carriesVatRate = false, so ustSatz stays null.
            lineItemsData = listOf(KassenbuchDto.LineItemData(vorzBetrag = amount, ustSatz = null)),
            tseTransaction = null,
        )
    }

    @Test
    fun `each book keeps its own running balance`() {
        val kasse = transaction { books.fiscalBookFor(shop).id.value }
        val tresor = transaction { books.create(shop, "Tresor", false, "1010").id.value }

        deposit(kasse, "100.00")
        deposit(tresor, "500.00")
        deposit(kasse, "50.00")

        assertEquals(0, BigDecimal("150.00").compareTo(transaction { repo.getCurrentBalance(shop, kasse) }))
        assertEquals(0, BigDecimal("500.00").compareTo(transaction { repo.getCurrentBalance(shop, tresor) }))
    }

    @Test
    fun `each book keeps its own belegNr series`() {
        val kasse = transaction { books.fiscalBookFor(shop).id.value }
        val tresor = transaction { books.create(shop, "Tresor", false, "1010").id.value }

        deposit(kasse, "100.00")
        deposit(kasse, "100.00")
        val tresorNr = transaction { repo.nextBelegNrBlocking(tresor) }

        assertEquals(1, tresorNr.substringAfterLast('-').toInt())
    }

    @Test
    fun `a null cashBookId draws from the fiscal book's series, not a global max`() {
        transaction { books.fiscalBookFor(shop) }
        val tresor = transaction { books.create(shop, "Tresor", false, "1010").id.value }

        // Three entries in the TRESOR only. Under the old global-max behaviour these would
        // advance the number every book sees, so the Ladenkasse's next Beleg would come back
        // as 0004 and its own Belegnummernkreis would be gapped 0001-0003.
        deposit(tresor, "10.00")
        deposit(tresor, "10.00")
        deposit(tresor, "10.00")

        val kasseNext = runBlocking { newSuspendedTransaction { repo.getNextBelegNr(shop) } }

        assertEquals(
            1,
            kasseNext.substringAfterLast('-').toInt(),
            "the Ladenkasse has no entries yet, so its first Beleg must be 0001 — got $kasseNext",
        )
    }
}

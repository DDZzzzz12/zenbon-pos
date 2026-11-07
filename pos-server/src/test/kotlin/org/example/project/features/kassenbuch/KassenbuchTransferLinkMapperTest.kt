package org.example.project.features.kassenbuch

import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.kassenbuch.data.repository.CashBookRepositoryImpl
import org.example.project.features.kassenbuch.data.repository.KassenbuchRepositoryImpl
import org.example.project.features.kassenbuch.domain.models.KassenbuchHeader
import org.example.project.features.kassenbuch.mapper.toGroupedDetailDto
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.example.project.testutils.testKassenbuchDocuments
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The transfer link the detail DTO carries: `transferId`, the counter-leg's header id, and the
 * counterparty book's name.
 *
 * The counter-leg id is what the client turns into a "Gegenbuchung" link, and it has no other way
 * to find one — no endpoint queries by `transferId`. So the two degenerate pairings are pinned
 * here as hard behaviour rather than left to chance: both report *no* counter-booking instead of
 * offering an arbitrary leg.
 */
class KassenbuchTransferLinkMapperTest {
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

    /** One leg of a transfer, booked into [bookId] with [counterpartyBookId] on the other side. */
    private fun leg(
        bookId: Long,
        counterpartyBookId: Long,
        transferId: String,
        amount: String,
    ): KassenbuchHeader =
        repo.createEntry(
            shop = shop,
            employee = employee,
            headerData =
                KassenbuchDto.KassenbuchData(
                    entryType =
                        if (amount.startsWith("-")) {
                            KassenbuchEntryType.CASH_TRANSFER_OUT
                        } else {
                            KassenbuchEntryType.CASH_TRANSFER_IN
                        },
                    belegNr = repo.nextBelegNrBlocking(bookId),
                    vorzBetrag = amount,
                    belegDatum = "01.08.2026",
                    belegtext = "Übertrag",
                    cashBookId = bookId,
                    counterpartyBookId = counterpartyBookId,
                    transferId = transferId,
                ),
            lineItemsData = listOf(KassenbuchDto.LineItemData(vorzBetrag = amount, ustSatz = null)),
            tseTransaction = null,
        )

    @Test
    fun `paired legs point at each other and name the counterparty book`() {
        transaction {
            val kasse = books.fiscalBookFor(shop).id.value
            val tresor = books.create(shop, "Tresor", false, "1010").id.value

            val out = leg(kasse, tresor, "transfer-1", "-100.00")
            val into = leg(tresor, kasse, "transfer-1", "100.00")

            val outDto = out.toGroupedDetailDto("EUR", linkedDocument = null)
            val inDto = into.toGroupedDetailDto("EUR", linkedDocument = null)

            assertEquals("transfer-1", outDto.transferId)
            assertEquals(into.id.value, outDto.counterpartyHeaderId)
            assertEquals("Tresor", outDto.counterpartyBookName)

            assertEquals(out.id.value, inDto.counterpartyHeaderId)
            assertEquals(books.fiscalBookFor(shop).name, inDto.counterpartyBookName)
        }
    }

    @Test
    fun `an entry with no transfer carries no link at all`() {
        transaction {
            val kasse = books.fiscalBookFor(shop).id.value
            val plain =
                repo.createEntry(
                    shop = shop,
                    employee = employee,
                    headerData =
                        KassenbuchDto.KassenbuchData(
                            entryType = KassenbuchEntryType.CASH_DEPOSIT,
                            belegNr = repo.nextBelegNrBlocking(kasse),
                            vorzBetrag = "10.00",
                            belegDatum = "01.08.2026",
                            belegtext = "Einlage",
                            cashBookId = kasse,
                        ),
                    lineItemsData = listOf(KassenbuchDto.LineItemData(vorzBetrag = "10.00", ustSatz = null)),
                    tseTransaction = null,
                )

            val dto = plain.toGroupedDetailDto("EUR", linkedDocument = null)
            assertNull(dto.transferId)
            assertNull(dto.counterpartyHeaderId)
            assertNull(dto.counterpartyBookName)
        }
    }

    @Test
    fun `a lone leg reports no counter-booking rather than linking to itself`() {
        transaction {
            val kasse = books.fiscalBookFor(shop).id.value
            val tresor = books.create(shop, "Tresor", false, "1010").id.value

            val orphan = leg(kasse, tresor, "transfer-orphan", "-100.00")

            val dto = orphan.toGroupedDetailDto("EUR", linkedDocument = null)
            assertEquals("transfer-orphan", dto.transferId)
            assertNull(dto.counterpartyHeaderId)
            // The book is still named — the transfer's destination is known even when its
            // partner row is not.
            assertEquals("Tresor", dto.counterpartyBookName)
        }
    }

    @Test
    fun `a third leg sharing the transfer id makes every leg report none`() {
        transaction {
            val kasse = books.fiscalBookFor(shop).id.value
            val tresor = books.create(shop, "Tresor", false, "1010").id.value

            val first = leg(kasse, tresor, "transfer-ambiguous", "-100.00")
            val second = leg(tresor, kasse, "transfer-ambiguous", "100.00")
            val third = leg(tresor, kasse, "transfer-ambiguous", "100.00")

            assertNull(first.toGroupedDetailDto("EUR", linkedDocument = null).counterpartyHeaderId)
            assertNull(second.toGroupedDetailDto("EUR", linkedDocument = null).counterpartyHeaderId)
            assertNull(third.toGroupedDetailDto("EUR", linkedDocument = null).counterpartyHeaderId)
        }
    }
}

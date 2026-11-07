package org.example.project.features.kassenbuch

import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.core.domain.enums.kassenbuch.KassenbuchLineKind
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.kassenbuch.data.repository.KassenbuchRepositoryImpl
import org.example.project.features.kassenbuch.domain.models.KassenbuchLineItem
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.example.project.testutils.testKassenbuchDocuments
import org.jetbrains.exposed.v1.dao.flushCache
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KassenbuchLineKindPersistenceTest {
    private lateinit var repository: KassenbuchRepositoryImpl
    private lateinit var shop: ShopInfo
    private lateinit var employee: Employee

    @BeforeEach
    fun setup() {
        TestDatabase.setup()
        repository = KassenbuchRepositoryImpl(testKassenbuchDocuments())
        shop = TestData.createShopInfo()
        employee = TestData.createAdmin()
    }

    @AfterEach
    fun teardown() = TestDatabase.teardown()

    @Test
    fun `persists kind and payment type on line items`() {
        val pt = TestData.createPaymentType(name = "CARD", isCash = false)
        transaction {
            val header =
                repository.createEntry(
                    shop = shop,
                    employee = employee,
                    headerData =
                        KassenbuchDto.KassenbuchData(
                            entryType = KassenbuchEntryType.CASH_SALE,
                            belegNr = "2026-07-0001",
                            vorzBetrag = "80.0",
                            belegDatum = "08.07.2026",
                            belegtext = "ZBon Nr. 1",
                        ),
                    lineItemsData =
                        listOf(
                            KassenbuchDto.LineItemData(vorzBetrag = "100.0", ustSatz = "19"),
                            KassenbuchDto.LineItemData(
                                vorzBetrag = "-20.0",
                                belegtext = "TE:Unbare Zahlung: CARD",
                                kind = KassenbuchLineKind.NON_CASH_PAYMENT,
                                paymentTypeId = pt.id.value,
                            ),
                        ),
                )
            val lines = header.lineItems.sortedBy { it.lineNumber }
            assertEquals(KassenbuchLineKind.STANDARD, lines[0].kind)
            assertNull(lines[0].paymentType)
            assertEquals(KassenbuchLineKind.NON_CASH_PAYMENT, lines[1].kind)
            assertEquals(pt.id.value, lines[1].paymentType?.id?.value)
        }
    }

    @Test
    fun `mixed-sign lines accumulate signed sub-balances and header balance`() {
        transaction {
            val header =
                repository.createEntry(
                    shop = shop,
                    employee = employee,
                    headerData =
                        KassenbuchDto.KassenbuchData(
                            entryType = KassenbuchEntryType.CASH_EXPENSE,
                            belegNr = "2026-07-0002",
                            vorzBetrag = "-30.56",
                            belegDatum = "08.07.2026",
                            belegtext = "Metro Wareneinkauf",
                        ),
                    lineItemsData =
                        listOf(
                            KassenbuchDto.LineItemData(vorzBetrag = "-23.78", ustSatz = "19"),
                            KassenbuchDto.LineItemData(vorzBetrag = "-131.78", ustSatz = "7"),
                            KassenbuchDto.LineItemData(vorzBetrag = "19.11", ustSatz = "19", kind = KassenbuchLineKind.GUTSCHEIN),
                            KassenbuchDto.LineItemData(vorzBetrag = "105.89", ustSatz = "7", kind = KassenbuchLineKind.GUTSCHEIN),
                        ),
                )
            assertEquals(0, BigDecimal("-30.56").compareTo(header.totalAmount))
            assertEquals(0, BigDecimal("-30.56").compareTo(header.currentBalance)) // opening balance 0
            val subs = header.lineItems.sortedBy { it.lineNumber }.map { it.subBalance }
            val expected = listOf("-23.78", "-155.56", "-136.45", "-30.56").map { BigDecimal(it) }
            expected.zip(subs).forEachIndexed { i, (exp, act) ->
                assertEquals(0, exp.compareTo(act), "subBalance[$i]: expected $exp, got $act")
            }
        }
    }

    @Test
    fun `negative net-cash entry decreases the balance where legacy abs math would have increased it`() {
        // Pins the deliberate behavioral fix of the signed-accumulation change:
        // a mixed-sign entry whose TOTAL sign opposes the header direction (the
        // Z-Bon shape — a CASH_SALE day where non-cash tenders exceed cash revenue)
        // must DECREASE the balance. The old "isIncoming ? +abs : -abs" math would
        // have booked +30.00 here instead of -30.00.
        transaction {
            val header =
                repository.createEntry(
                    shop = shop,
                    employee = employee,
                    headerData =
                        KassenbuchDto.KassenbuchData(
                            entryType = KassenbuchEntryType.CASH_SALE,
                            belegNr = "2026-07-0004",
                            vorzBetrag = "-30.0",
                            belegDatum = "08.07.2026",
                            belegtext = "ZBon Nr. 3",
                        ),
                    lineItemsData =
                        listOf(
                            KassenbuchDto.LineItemData(vorzBetrag = "100.0", ustSatz = "19"),
                            KassenbuchDto.LineItemData(
                                vorzBetrag = "-130.0",
                                belegtext = "TE:Unbare Zahlung: CARD",
                                kind = KassenbuchLineKind.NON_CASH_PAYMENT,
                            ),
                        ),
                )
            assertEquals(0, BigDecimal("-30.00").compareTo(header.totalAmount))
            assertEquals(0, BigDecimal("-30.00").compareTo(header.currentBalance)) // opening balance 0
            val subs = header.lineItems.sortedBy { it.lineNumber }.map { it.subBalance }
            val expected = listOf("100.00", "-30.00").map { BigDecimal(it) }
            expected.zip(subs).forEachIndexed { i, (exp, act) ->
                assertEquals(0, exp.compareTo(act), "subBalance[$i]: expected $exp, got $act")
            }
        }
    }

    @Test
    fun `V88 backfill patterns classify historical belegtexte`() {
        transaction {
            repository.createEntry(
                shop = shop,
                employee = employee,
                headerData =
                    KassenbuchDto.KassenbuchData(
                        entryType = KassenbuchEntryType.CASH_SALE,
                        belegNr = "2026-07-0003",
                        vorzBetrag = "60.0",
                        belegDatum = "08.07.2026",
                        belegtext = "ZBon Nr. 2",
                    ),
                lineItemsData =
                    listOf(
                        KassenbuchDto.LineItemData(vorzBetrag = "100.0", ustSatz = "19", belegtext = "TE19%"),
                        KassenbuchDto.LineItemData(vorzBetrag = "-25.0", belegtext = "TE:Unbare Zahlung: CARD"),
                        KassenbuchDto.LineItemData(vorzBetrag = "-15.0", belegtext = "Unbare Zahlung: EC mit ZBon Nr. 3"),
                    ),
            )
            // Force the pending DAO inserts to hit the DB: exec() with a raw SQL string
            // builds a generic Statement, not an UpdateStatement/InsertStatement, so
            // Exposed's EntityLifecycleInterceptor does not auto-flush the entity cache
            // before it runs (unlike a DSL query/update, which does). Without this, the
            // raw UPDATE below would silently affect zero rows.
            flushCache()
            // Simulate pre-migration rows (kind column freshly defaulted), then run
            // the V88 backfill statement VERBATIM — keep in sync with the migration.
            exec("UPDATE kassenbuch_line_item SET kind = 'STANDARD'")
            exec(
                """
                UPDATE kassenbuch_line_item
                    SET kind = 'NON_CASH_PAYMENT'
                    WHERE belegtext LIKE 'TE:Unbare Zahlung%'
                       OR belegtext LIKE 'Unbare Zahlung%'
                """.trimIndent(),
            )
        }
        transaction {
            val lines = KassenbuchLineItem.all().sortedBy { it.lineNumber }
            assertEquals(KassenbuchLineKind.STANDARD, lines[0].kind) // "TE19%"
            assertEquals(KassenbuchLineKind.NON_CASH_PAYMENT, lines[1].kind) // TE: prefix (2026-03+)
            assertEquals(KassenbuchLineKind.NON_CASH_PAYMENT, lines[2].kind) // legacy prefix (pre 2026-03)
        }
    }
}

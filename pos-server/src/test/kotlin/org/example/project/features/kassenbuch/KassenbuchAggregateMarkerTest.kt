package org.example.project.features.kassenbuch

import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.kassenbuch.data.repository.KassenbuchRepositoryImpl
import org.example.project.features.kassenbuch.mapper.toGroupedDetailDto
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.example.project.testutils.testKassenbuchDocuments
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KassenbuchAggregateMarkerTest {
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
    fun `aggregate flag persists and surfaces on the detail dto, manual entries default false`() {
        transaction {
            val aggregate =
                repository.createEntry(
                    shop = shop,
                    employee = employee,
                    headerData =
                        KassenbuchDto.KassenbuchData(
                            entryType = KassenbuchEntryType.CASH_SALE,
                            belegNr = "2026-07-0001",
                            vorzBetrag = "50.0",
                            belegDatum = "09.07.2026",
                            belegtext = "ZBon Nr. 1",
                            isZbonAggregate = true,
                        ),
                    lineItemsData = listOf(KassenbuchDto.LineItemData(vorzBetrag = "50.0", ustSatz = "19")),
                )
            assertTrue(aggregate.isZbonAggregate)
            assertTrue(aggregate.toGroupedDetailDto("EUR", linkedDocument = null).isZbonAggregate)

            val manual =
                repository.createEntry(
                    shop = shop,
                    employee = employee,
                    headerData =
                        KassenbuchDto.KassenbuchData(
                            entryType = KassenbuchEntryType.CASH_EXPENSE,
                            belegNr = "2026-07-0002",
                            vorzBetrag = "-10.0",
                            belegDatum = "09.07.2026",
                            belegtext = "Barausgabe",
                        ),
                    lineItemsData = listOf(KassenbuchDto.LineItemData(vorzBetrag = "-10.0", ustSatz = "19")),
                )
            assertFalse(manual.isZbonAggregate)
            assertFalse(manual.toGroupedDetailDto("EUR", linkedDocument = null).isZbonAggregate)
        }
    }
}

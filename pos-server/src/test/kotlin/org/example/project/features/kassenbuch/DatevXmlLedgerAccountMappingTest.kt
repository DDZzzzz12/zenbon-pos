package org.example.project.features.kassenbuch

import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.enums.datev.Kontenrahmen
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.core.domain.enums.kassenbuch.KassenbuchLineKind
import org.example.project.core.utils.datev.DatevAccountMappingDefaults
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.kassenbuch.data.repository.KassenbuchRepositoryImpl
import org.example.project.features.kassenbuch.data.service.DatevXmlExportServiceImpl
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.example.project.testutils.testKassenbuchDocuments
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DatevXmlLedgerAccountMappingTest {
    private lateinit var repository: KassenbuchRepositoryImpl
    private lateinit var shop: ShopInfo
    private lateinit var employee: Employee

    private inline fun <reified T : Any> noopProxy(): T {
        @Suppress("UNCHECKED_CAST")
        return Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            throw NotImplementedError("Stub: ${T::class.simpleName}.${method.name} not expected here")
        } as T
    }

    private val service by lazy {
        DatevXmlExportServiceImpl(
            kassenbuchRepository = repository,
            shopInfoRepository = noopProxy(),
            zBonService = noopProxy(),
            configManager = noopProxy(),
            exportLogService = noopProxy(),
            documentRepository = noopProxy(),
        )
    }

    @BeforeEach
    fun setup() {
        TestDatabase.setup()
        repository = KassenbuchRepositoryImpl(testKassenbuchDocuments())
        shop = TestData.createShopInfo()
        employee = TestData.createAdmin()
    }

    @AfterEach
    fun teardown() = TestDatabase.teardown()

    private fun saleWithGutschein() =
        repository.createEntry(
            shop = shop,
            employee = employee,
            headerData =
                KassenbuchDto.KassenbuchData(
                    entryType = KassenbuchEntryType.CASH_SALE,
                    belegNr = "2026-07-0200",
                    vorzBetrag = "80.0",
                    belegDatum = "08.07.2026",
                    belegtext = "Barverkauf",
                ),
            lineItemsData =
                listOf(
                    KassenbuchDto.LineItemData(vorzBetrag = "100.0", ustSatz = "19"),
                    KassenbuchDto.LineItemData(vorzBetrag = "-20.0", ustSatz = "19", kind = KassenbuchLineKind.GUTSCHEIN),
                ),
        )

    @Test
    fun `mapped accounts appear per line, legacy path keeps raw header fields`() {
        val mapping = DatevAccountMappingDefaults.seed(Kontenrahmen.SKR03, emptyList())
        transaction {
            val header = saleWithGutschein()

            val mapped = service.mapHeaderToEntries(header, mapping)
            assertEquals("8400", mapped[0].accountNo) // Erlöse 19 %
            assertEquals("8720", mapped[1].accountNo) // Erlösschmälerungen 19 %
            assertNull(mapped[0].buCode)

            val legacy = service.mapHeaderToEntries(header, null)
            assertNull(legacy[0].accountNo) // raw header.gegenkonto (unset) — byte-identical to today
            assertNull(legacy[1].accountNo)
        }
    }
}

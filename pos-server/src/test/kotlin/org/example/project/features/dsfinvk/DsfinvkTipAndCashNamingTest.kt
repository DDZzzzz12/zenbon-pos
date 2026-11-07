package org.example.project.features.dsfinvk

import kotlinx.serialization.json.Json
import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto
import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto.BusinessCaseNames
import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto.BusinessCaseTypes
import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.core.domain.enums.kassenbuch.KassenbuchLineKind
import org.example.project.export.dsfinvk.builder.KassenbuchDsfinvkMapping
import org.example.project.features.dsfinvk.data.service.DsfinvkExportServiceImpl
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.kassenbuch.data.repository.KassenbuchRepositoryImpl
import org.example.project.features.kassenbuch.domain.models.KassenbuchHeader
import org.example.project.features.salesinvoice.data.service.SalesInvoiceBillInvoiceLink
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.zBon.domain.models.ZBon
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.example.project.testutils.testKassenbuchDocuments
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * F54 #2 and #3 — one name per thing in the DSFinV-K export.
 *
 * #2: `TrinkgeldAN` was "Trinkgeld (AN)" on bill rows and "Trinkgeld Arbeitnehmer" on Kassenbuch
 * rows. Both now read [BusinessCaseNames.TRINKGELD_AN], the bill-side label that shipped first.
 *
 * #3: a Kassenbuch cash tender hardcoded ZAHLART_NAME "Bar" while a cash bill carries its stored
 * payment-type name (an operator's "BAR"), so payment.csv listed cash twice in a mixed closing and
 * under a different casing in a Kassenbuch-only one. The Kassenbuch row now carries the stored name.
 */
@DisplayName("DSFinV-K: one GV_NAME for TrinkgeldAN, one ZAHLART_NAME for cash (F54)")
class DsfinvkTipAndCashNamingTest {
    private lateinit var repository: KassenbuchRepositoryImpl
    private lateinit var shop: ShopInfo
    private lateinit var employee: Employee
    private lateinit var zBon: ZBon
    private val service =
        DsfinvkExportServiceImpl(
            json = Json { ignoreUnknownKeys = true },
            configRepository = ConfigurationRepository(),
            invoices = SalesInvoiceBillInvoiceLink(),
        )

    @BeforeEach
    fun setup() {
        TestDatabase.setup()
        repository = KassenbuchRepositoryImpl(testKassenbuchDocuments())
        shop = TestData.createShopInfo()
        employee = TestData.createAdmin()
        zBon = TestData.createZBonFullDay(shop, employee)
    }

    @AfterEach
    fun teardown() = TestDatabase.teardown()

    private fun withdrawal(belegNr: String): KassenbuchHeader =
        repository
            .createEntry(
                shop = shop,
                employee = employee,
                headerData =
                    KassenbuchDto.KassenbuchData(
                        entryType = KassenbuchEntryType.CASH_WITHDRAWAL,
                        belegNr = belegNr,
                        vorzBetrag = "-12.50",
                        belegDatum = "04.10.2026",
                        belegtext = "TA:Trinkgeld-Auszahlung Anna",
                    ),
                lineItemsData =
                    listOf(KassenbuchDto.LineItemData(vorzBetrag = "-12.50", ustSatz = null, kind = KassenbuchLineKind.EMPLOYEE_TIP)),
            ).also { it.zBon = zBon }

    private fun kassenbuchCashRow(): DsfinvkExportDto.BonkopfZahlartenRowDto =
        transaction {
            service
                .generateAvPayments(emptyList(), emptyList(), listOf(withdrawal("2026-10-0101")), listOf(zBon), "K1")
                .single()
        }

    @Test
    fun `the Kassenbuch side names TrinkgeldAN exactly as the bill side does`() {
        assertEquals(BusinessCaseNames.TRINKGELD_AN, KassenbuchDsfinvkMapping.getGvName(BusinessCaseTypes.TRINKGELD_AN))
        assertEquals("Trinkgeld (AN)", BusinessCaseNames.TRINKGELD_AN, "the label bill-side exports have carried since tips shipped")
    }

    @Test
    fun `a Kassenbuch cash tender carries the stored cash payment-type name`() {
        TestData.createPaymentType(name = "BAR", isCash = true)

        val row = kassenbuchCashRow()

        assertEquals(DsfinvkExportDto.PaymentTypes.BAR, row.zahlartTyp, "ZAHLART_TYP stays the DSFinV-K code")
        assertEquals("BAR", row.zahlartName)
    }

    @Test
    fun `an inactive or voucher cash type does not win over the active cash type`() {
        TestData.createPaymentType(name = "Altes Bargeld", isCash = true, isActive = false)
        TestData.createPaymentType(name = "Bargeld", isCash = true)

        assertEquals("Bargeld", kassenbuchCashRow().zahlartName)
    }

    @Test
    fun `without any cash payment type the canonical Bar is used`() {
        TestData.createPaymentType(name = "EC-KARTE", isCash = false)

        assertEquals(DsfinvkExportDto.PaymentTypes.BAR, kassenbuchCashRow().zahlartName)
    }

    /** The visible symptom: a closing with a cash bill and a cash Kassenbuch entry lists cash ONCE. */
    @Test
    fun `payment csv lists cash once when a bill and a Kassenbuch entry share a closing`() {
        TestData.createPaymentType(name = "BAR", isCash = true)
        val kassenbuchRow = kassenbuchCashRow()
        val billRow =
            kassenbuchRow.copy(bonId = "bill-1", zahlartName = "BAR", basiswaehBetrag = "30.00")

        val summary =
            transaction { service.generatePaymentSummaries(listOf(zBon), "K1", listOf(billRow, kassenbuchRow), emptySet()) }

        val cash = summary.filter { it.zahlartTyp == DsfinvkExportDto.PaymentTypes.BAR }
        assertEquals(1, cash.size, "one Bar row, not BAR + Bar: $summary")
        assertEquals("BAR", cash.single().zahlartName)
    }
}

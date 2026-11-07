package org.example.project.features.zBon

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.payment.PaymentDto
import org.example.project.core.domain.dto.report.ZBonDto
import org.example.project.core.domain.enums.bill.TipRecipient
import org.example.project.core.domain.enums.employee.EmployeeRole
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.core.domain.enums.rTable.TableStatus
import org.example.project.core.utils.zBonTipSections
import org.example.project.features.bill.data.service.BillNumberTracker
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.bill.domain.service.BillService
import org.example.project.features.dsfinvk.domain.service.DsfinvkExportService
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.table.domain.models.RTable
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.zBon.domain.models.ZBon
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.testutils.testJson
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Z-Bon decides owner vs employee tip by each bill's stored `tipRecipient` — the field the
 * bill's VAT rows, DSFinV-K (`tipRecipient == OWNER`) and Statistik key off — not by the closer's
 * CURRENT role. Deciding by role made `adminTip`/`totalPosRevenue` disagree with the VAT rows and
 * DSFinV-K whenever an employee was promoted or demoted between the sale and the closing.
 *
 * Each case closes one 20.00 bill with a tip through the real [BillService], changes the closer's
 * role, then creates the Z-Bon.
 */
@DisplayName("Z-Bon — tips follow the bill's tipRecipient across a role change")
class ZBonTipRoleChangeTest {
    private val deviceId = "zbon-tip-role-change-device"
    private var billId: Long = 0

    @AfterEach
    fun resetBillNumberTracker() = BillNumberTracker.clearAll()

    @Test
    fun `a MANAGER promoted to ADMIN after the sale still has an employee tip on the Z-Bon`() =
        testApp(setupData = { setupDay(closerIsAdmin = false, roleAfterSale = EmployeeRole.ADMIN) }) { client ->
            assertRecipient(TipRecipient.EMPLOYEE)

            val dto = createClosing(client)
            val closer = dto.employeeSummaries.orEmpty().single { it.employeeNumber == CLOSER }

            assertEquals("0.00", dto.adminTip, "an employee tip is not the owner's, whatever the role today")
            assertEquals("3.00", dto.totalEmployeeTips)
            assertEquals(0, BigDecimal("3.00").compareTo(BigDecimal(closer.amount)))
            assertEquals(false, closer.ownerTip)
            assertEquals("20.00", dto.totalRevenue, "the employee tip is not revenue")
            assertRevenueAgrees(dto)
            assertEquals(listOf(CLOSER), zBonTipSections(dto).employeeTips.map { it.employeeNumber })
        }

    @Test
    fun `an ADMIN demoted after an owner-tip sale keeps the owner tip in adminTip and revenue`() =
        testApp(setupData = { setupDay(closerIsAdmin = true, roleAfterSale = EmployeeRole.MANAGER) }) { client ->
            assertRecipient(TipRecipient.OWNER)

            val dto = createClosing(client)
            val closer = dto.employeeSummaries.orEmpty().single { it.employeeNumber == CLOSER }

            assertEquals("3.00", dto.adminTip, "an owner tip stays the owner's, whatever the role today")
            assertEquals(0, BigDecimal.ZERO.compareTo(BigDecimal(closer.amount)), "no owner tip on a summary")
            assertEquals("0.00", dto.totalEmployeeTips)
            assertEquals("23.00", dto.totalRevenue, "the owner tip is taxed turnover")
            assertRevenueAgrees(dto)
            val tips = zBonTipSections(dto)
            assertTrue(tips.employeeTips.isEmpty(), "the owner tip is not listed as an employee tip")
            assertEquals("3.00", tips.ownerTip)
        }

    /**
     * A closing created BEFORE summaries stopped carrying owner tips stored the admin's owner tip on
     * the admin's summary too. Re-rendering one must still keep it out of the employee list.
     */
    @Test
    fun `a legacy closing whose ADMIN summary carries the owner tip still renders coherently`() =
        testApp(setupData = { setupDay(closerIsAdmin = true, roleAfterSale = EmployeeRole.ADMIN) }) { client ->
            val created = createClosing(client)
            transaction {
                // Rewrite the stored summary into the pre-change shape: owner tip on the ADMIN row.
                ZBon
                    .findById(created.id)!!
                    .employeeSummaries
                    .single()
                    .tipAmount = BigDecimal("3.0000")
            }

            val dto = getClosing(client, created.id)
            val admin = dto.employeeSummaries.orEmpty().single()

            assertEquals(true, admin.ownerTip, "a legacy ADMIN summary's tip is the owner's")
            assertEquals(null, dto.totalEmployeeTips, "and is not totalled as an employee tip")
            assertEquals("3.00", dto.adminTip)
            assertEquals("23.00", dto.totalRevenue, "stored fiscal figures are untouched")
            val tips = zBonTipSections(dto)
            assertTrue(tips.employeeTips.isEmpty())
            assertEquals("3.00", tips.ownerTip)
        }

    // ============================================
    // Helpers
    // ============================================

    private fun assertRecipient(expected: TipRecipient) = transaction { assertEquals(expected, Bill.findById(billId)!!.tipRecipient, "fixture sanity") }

    /** The Z-Bon's revenue equals its VAT gross and the DSFinV-K export's revenue for the day. */
    private fun assertRevenueAgrees(dto: ZBonDto.ZBonResponseDto) {
        val vatGross = assertNotNull(dto.vatGroups).sumOf { BigDecimal(it.grossAmount) }
        assertEquals(0, BigDecimal(dto.totalRevenue).compareTo(vatGross), "Z-Bon revenue = VAT gross")
        val today = currentTimeStamp().date.toString()
        val dsfinvk =
            runBlocking {
                GlobalContext.get().get<DsfinvkExportService>().getExportStatistics(today, today)
            }
        assertEquals(
            0,
            BigDecimal(dto.totalRevenue).compareTo(BigDecimal(dsfinvk.totalRevenue)),
            "Z-Bon revenue = DSFinV-K revenue (${dsfinvk.totalRevenue})",
        )
    }

    private suspend fun createClosing(client: HttpClient): ZBonDto.ZBonResponseDto {
        val token = TestAuthHelper.loginAs(client, "admin", deviceId = deviceId)
        val response = client.post("/api/z-bon") { bearerAuth(token) }
        assertEquals(HttpStatusCode.Created, response.status, "body: ${response.bodyAsText()}")
        return testJson.decodeFromString<ZBonDto.ZBonResponseDto>(response.bodyAsText())
    }

    private suspend fun getClosing(
        client: HttpClient,
        id: Long,
    ): ZBonDto.ZBonResponseDto {
        val token = TestAuthHelper.loginAs(client, "admin", deviceId = deviceId)
        val response = client.get("/api/z-bon/$id") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, response.status, "body: ${response.bodyAsText()}")
        return testJson.decodeFromString<ZBonDto.ZBonResponseDto>(response.bodyAsText())
    }

    /**
     * One 20.00 item with a 3.00 cash tip closed by [CLOSER] (an ADMIN when [closerIsAdmin], else a
     * MANAGER), whose role is then set to [roleAfterSale]. The Z-Bon is created by a separate admin.
     */
    private fun setupDay(
        closerIsAdmin: Boolean,
        roleAfterSale: EmployeeRole,
    ) {
        TestData.createShopInfo()
        TestData.createAdmin(employeeNumber = "ZRC_ADM", username = "admin")
        val closer: Employee =
            if (closerIsAdmin) {
                TestData.createAdmin(employeeNumber = CLOSER, username = "zrc_closer")
            } else {
                TestData.createManager(employeeNumber = CLOSER, username = "zrc_closer")
            }
        val vat19 = TestData.createDefaultVatRates().first()
        val cash = TestData.createPaymentType("Cash_ZRC", isCash = true)
        val tws = TestData.createTableWithSession("ZRC1")
        val category = TestData.createCategory("ZRC Cat")
        val product = TestData.createProduct(category.id.value, "ZRC Product", "ZRC_P", BigDecimal("20.0000"))
        TestData.createFiscalRegisteredTerminal(deviceId = deviceId, clientId = "TERM-ZRC")

        val itemId =
            transaction {
                // H2 stand-in for the Postgres advisory lock used to serialize Z-Bon creation.
                exec("CREATE ALIAS IF NOT EXISTS PG_ADVISORY_XACT_LOCK AS 'long pgAdvisoryXactLock(long key) { return key; }'")
                val session = TableSession.findById(tws.session.id)!!
                session.employeeId = closer
                OrderItem
                    .new {
                        this.session = session
                        this.product = product
                        this.unitPrice = BigDecimal("20.00")
                        this.finalPrice = BigDecimal("20.00")
                        this.itemStatus = ItemStatus.OPEN
                        this.vat = vat19
                        this.discount = BigDecimal.ONE
                        this.customPrice = false
                        this.takeAway = false
                        this.transferred = false
                        this.sideNotes = null
                    }.id.value
            }

        billId =
            runBlocking {
                GlobalContext
                    .get()
                    .get<BillService>()
                    .createBill(
                        employeeId = closer.id.value.toString(),
                        deviceId = deviceId,
                        request =
                            BillDto.CreateBillRequestDto(
                                tableSessionId = tws.session.id.value,
                                orderItems = listOf(itemId),
                                billAmount = "20.00",
                                tips = "3.00",
                                payments = listOf(PaymentDto.PaymentRequest(amount = "23.00", paymentTypeId = cash.id.value)),
                            ),
                    ).billId
            }

        transaction {
            Employee.findById(closer.id)!!.role = roleAfterSale
            val session = TableSession.findById(tws.session.id)!!
            val table = RTable.findById(tws.table.id)!!
            session.completed = true
            session.endTime = currentTimeStamp()
            table.currentSession = null
            table.tableStatus = TableStatus.FREE
        }
    }

    private companion object {
        const val CLOSER = "ZRC_CLS"
    }
}

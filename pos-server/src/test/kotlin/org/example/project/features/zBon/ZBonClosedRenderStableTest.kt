package org.example.project.features.zBon

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.payment.PaymentDto
import org.example.project.core.domain.dto.report.ZBonDto
import org.example.project.core.domain.enums.employee.EmployeeRole
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.core.domain.enums.rTable.TableStatus
import org.example.project.core.domain.enums.setup.Language
import org.example.project.features.bill.data.service.BillNumberTracker
import org.example.project.features.bill.domain.service.BillService
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.table.domain.models.RTable
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.zBon.domain.service.ZBonService
import org.example.project.pdf.generator.generateZBonReportPdfBytes
import org.example.project.printer.core.formatter.ZBonFormatter
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
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * QA emu16 NOTE-4: a CLOSED Z-Bon must render the same every time.
 *
 * The paper Z-Bon printed an employee's revenue row as `M001 (ADMIN)`; after that employee's role
 * was changed, `GET /api/z-bon/{id}` returned the CURRENT role (joined live from `employees`, not
 * stored on the closing), so a reprint, a regenerated PDF and the detail panels drifted from the
 * original paper. The renderings no longer show the role; this pins that the paper, the PDF and
 * the DTO's rendered fields are identical before and after the employee's role AND name change.
 */
@DisplayName("Z-Bon — a closed Z-Bon renders identically after the employee's role and name change")
class ZBonClosedRenderStableTest {
    private val deviceId = "zbon-render-stable-device"
    private lateinit var sellerId: UUID

    @AfterEach
    fun resetBillNumberTracker() = BillNumberTracker.clearAll()

    @Test
    fun `reprint, PDF and DTO of a closed Z-Bon do not follow a later role or name change`() =
        testApp(setupData = { setupDay() }) { client ->
            val created = createClosing(client)
            val before = getClosing(client, created.id)
            val paperBefore = paper(before)
            val pdfBefore = pdfText(before)

            assertTrue(paperBefore.any { it.startsWith(SELLER) && it.trimEnd().endsWith("20,00 EUR") }, "fixture sanity: ${paperBefore.joinToString("\n")}")

            transaction {
                val seller = Employee.findById(sellerId)!!
                seller.role = EmployeeRole.ADMIN
                seller.username = "renamed_seller"
            }

            val after = getClosing(client, created.id)
            assertEquals(before.employeeNumber, after.employeeNumber, "the closer's number is stored, not joined")
            assertEquals(renderedFields(before), renderedFields(after), "the DTO's rendered fields are unchanged")

            val paperAfter = paper(after)
            assertEquals(paperBefore, paperAfter, "the reprint matches the original paper")
            assertTrue(paperAfter.none { "(ADMIN)" in it || "(MANAGER)" in it }, "no role label on the paper")

            val pdfAfter = pdfText(after)
            assertEquals(pdfBefore, pdfAfter, "the regenerated PDF matches the original")
            val served = textOf(GlobalContext.get().get<ZBonService>().getZBonPdfBytes(created.id))
            assertTrue("(ADMIN)" !in served && "(MANAGER)" !in served, "no role label in the served PDF:\n$served")
            assertTrue("renamed_seller" !in served, "the served PDF does not follow a rename")
        }

    // ============================================
    // Helpers
    // ============================================

    /** The DTO minus the two summary fields no rendering shows: the live role and live name. */
    private fun renderedFields(dto: ZBonDto.ZBonResponseDto) =
        dto.copy(
            employeeSummaries = dto.employeeSummaries?.map { it.copy(role = "", employeeName = "") },
            // F51: the tip-payout positions name the employee as they are NOW — they are the till's
            // "who do I hand the cash to" list, not part of the printed closing — so the name is
            // excluded here exactly like the summary's. The amounts stay compared.
            tipPayouts = dto.tipPayouts.map { it.copy(employeeName = "") },
        )

    private fun paper(dto: ZBonDto.ZBonResponseDto): List<String> =
        runBlocking { ZBonFormatter().format(dto, Language.GERMAN) }
            .toString(charset("Cp850"))
            .replace(Regex("[\\u0000-\\u0009\\u000b-\\u001f\\u007f]"), "")
            .lines()

    private fun pdfText(dto: ZBonDto.ZBonResponseDto): String = textOf(generateZBonReportPdfBytes(dto))

    private fun textOf(bytes: ByteArray): String = Loader.loadPDF(bytes).use { PDFTextStripper().getText(it) }

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

    /** One 20.00 item with a 3.00 tip sold and closed by the MANAGER [SELLER]; an admin closes the day. */
    private fun setupDay() {
        TestData.createShopInfo()
        TestData.createAdmin(employeeNumber = "ZRS_ADM", username = "admin")
        val seller = TestData.createManager(employeeNumber = SELLER, username = "zrs_seller")
        sellerId = seller.id.value
        val vat19 = TestData.createDefaultVatRates().first()
        val cash = TestData.createPaymentType("Cash_ZRS", isCash = true)
        val tws = TestData.createTableWithSession("ZRS1")
        val category = TestData.createCategory("ZRS Cat")
        val product = TestData.createProduct(category.id.value, "ZRS Product", "ZRS_P", BigDecimal("20.0000"))
        TestData.createFiscalRegisteredTerminal(deviceId = deviceId, clientId = "TERM-ZRS")

        val itemId =
            transaction {
                // H2 stand-in for the Postgres advisory lock used to serialize Z-Bon creation.
                exec("CREATE ALIAS IF NOT EXISTS PG_ADVISORY_XACT_LOCK AS 'long pgAdvisoryXactLock(long key) { return key; }'")
                val session = TableSession.findById(tws.session.id)!!
                session.employeeId = seller
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

        runBlocking {
            GlobalContext
                .get()
                .get<BillService>()
                .createBill(
                    employeeId = seller.id.value.toString(),
                    deviceId = deviceId,
                    request =
                        BillDto.CreateBillRequestDto(
                            tableSessionId = tws.session.id.value,
                            orderItems = listOf(itemId),
                            billAmount = "20.00",
                            tips = "3.00",
                            payments = listOf(PaymentDto.PaymentRequest(amount = "23.00", paymentTypeId = cash.id.value)),
                        ),
                )
        }

        transaction {
            val session = TableSession.findById(tws.session.id)!!
            val table = RTable.findById(tws.table.id)!!
            session.completed = true
            session.endTime = currentTimeStamp()
            table.currentSession = null
            table.tableStatus = TableStatus.FREE
        }
    }

    private companion object {
        const val SELLER = "ZRS_SEL"
    }
}

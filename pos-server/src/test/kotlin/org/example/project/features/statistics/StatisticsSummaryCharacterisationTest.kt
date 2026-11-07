package org.example.project.features.statistics

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.atTime
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.example.project.core.domain.dto.statistics.StatisticsDto
import org.example.project.core.domain.enums.bill.PaymentStatus
import org.example.project.core.domain.enums.bill.StornoReason
import org.example.project.core.domain.enums.bill.TipRecipient
import org.example.project.core.domain.enums.promotion.DiscountType
import org.example.project.core.domain.enums.promotion.PromotionScope
import org.example.project.core.domain.enums.rTable.TableStatus
import org.example.project.core.domain.enums.salesinvoice.RevenueOrigin
import org.example.project.core.domain.enums.salesinvoice.SalesInvoiceStatus
import org.example.project.features.bill.data.service.MapperBillSummaries
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.bill.domain.models.BillVatDetail
import org.example.project.features.employee.database.tables.EmployeeInfoTable
import org.example.project.features.employee.database.tables.EmployeeTable
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.payment.domain.models.Payment
import org.example.project.features.payment.domain.models.PaymentType
import org.example.project.features.promotion.domain.models.BillPromotion
import org.example.project.features.promotion.domain.models.Promotion
import org.example.project.features.salesinvoice.data.service.SalesInvoiceBillInvoiceLink
import org.example.project.features.salesinvoice.domain.models.SalesInvoice
import org.example.project.features.statistics.data.repository.StatisticsRepositoryImpl
import org.example.project.features.table.domain.models.RTable
import org.example.project.features.table.domain.models.TableSession
import org.example.project.testutils.TableWithSession
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals

/**
 * Pins the serialised JSON of the bill and session summaries the statistics repository builds
 * ([StatisticsRepositoryImpl.getPaginatedBills], `getSessionSummariesForTable`,
 * `getPaginatedEmployeeGroups`, `getPaginatedSessions` with `hrNames` false and true), so moving
 * their construction behind an ecr port cannot change a byte of the wire.
 *
 * Session A (operator with a personnel record "Kern"): a sale carrying a bill promotion, a
 * voucher-tender sale, and the Storno of the voucher sale. Session B (operator without one): a sale
 * "auf Rechnung" and the receivable-clearing bill that settles its invoice.
 *
 * Written green on the pre-port code; must never be edited by the refactor.
 */
@DisplayName("Statistics bill and session summaries serialise byte-for-byte as before")
class StatisticsSummaryCharacterisationTest {
    private val repo = StatisticsRepositoryImpl(MapperBillSummaries(SalesInvoiceBillInvoiceLink()))
    private val json = Json
    private val start = LocalDate(2026, 3, 1).atTime(0, 0, 0)
    private val end = LocalDate(2026, 3, 31).atTime(23, 59, 59)

    private var tableIdA: Long = 0L

    @Test
    fun `paginated bills`() =
        testApp(setupData = { seed() }) { _ ->
            val actual = transaction { json.encodeToString(repo.getPaginatedBills(start, end, null, 0, 50)) }
            assertEquals(EXPECTED_BILLS, actual)
        }

    @Test
    fun `table session summaries`() =
        testApp(setupData = { seed() }) { _ ->
            val actual =
                transaction {
                    json.encodeToString(repo.getSessionSummariesForTable(tableIdA, start, end))
                }
            assertEquals(EXPECTED_TABLE_SESSIONS, actual)
        }

    @Test
    fun `employee groups without hr names`() = employeeGroups(false, EXPECTED_GROUPS_PLAIN)

    @Test
    fun `employee groups with hr names`() = employeeGroups(true, EXPECTED_GROUPS_HR)

    @Test
    fun `sessions without hr names`() = sessions(false, EXPECTED_SESSIONS_PLAIN)

    @Test
    fun `sessions with hr names`() = sessions(true, EXPECTED_SESSIONS_HR)

    private fun employeeGroups(
        hrNames: Boolean,
        expected: String,
    ) = testApp(setupData = { seed() }) { _ ->
        val actual =
            transaction {
                json
                    .encodeToString(
                        repo.getPaginatedEmployeeGroups(
                            start,
                            end,
                            null,
                            0,
                            50,
                            StatisticsDto.StatisticsSortOption.RECENT,
                            false,
                            null,
                            hrNames,
                        ),
                    ).withoutUuids()
            }
        assertEquals(expected, actual)
    }

    private fun sessions(
        hrNames: Boolean,
        expected: String,
    ) = testApp(setupData = { seed() }) { _ ->
        val actual = transaction { json.encodeToString(repo.getPaginatedSessions(start, end, null, 0, 50, false, null, hrNames)).withoutUuids() }
        assertEquals(expected, actual)
    }

    /** Employee ids are random UUIDs; everything else in the JSON is deterministic. */
    private fun String.withoutUuids(): String = replace(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"), "<uuid>")

    private fun seed() {
        val admin = TestData.createAdmin()
        val shop = TestData.createShopInfo()
        val vat19 = TestData.createDefaultVatRates()[0]
        val cash = TestData.createPaymentType("Bar", isCash = true)
        val voucherTender = TestData.createPaymentType("Gutschein", isCash = false, isVoucher = true)
        val onAccount = TestData.createPaymentType("Rechnung", isCash = false)
        val card = TestData.createPaymentType("Karte", isCash = false)
        val twsA = TestData.createTableWithSession("SA")
        val twsB = TestData.createTableWithSession("SB")
        tableIdA = twsA.table.id.value

        transaction {
            val receivableTender = PaymentType.findById(onAccount.id)!!.apply { createsReceivable = true }
            val sessionA = closed(twsA, T_0900, T_1300)
            val sessionB = closed(twsB, T_0930, T_1330)
            val operatorA = sessionA.employeeId
            val operatorB = sessionB.employeeId
            hrRecord(operatorA, "Klara", "Kern")

            fun sale(
                employee: Employee,
                session: TableSession,
                number: String,
                amount: String,
                at: LocalDateTime,
                tender: PaymentType,
            ): Bill {
                val bill =
                    Bill.new {
                        this.employeeId = employee
                        this.tableSession = session
                        this.billNumber = number
                        this.billAmount = BigDecimal(amount)
                        this.paidAmount = BigDecimal(amount)
                        this.tips = BigDecimal.ZERO
                        this.tipRecipient = TipRecipient.OWNER
                        this.startTime = at
                        this.endTime = at
                    }
                BillVatDetail.new {
                    this.bill = bill
                    this.vat = vat19
                    this.netAmount = BigDecimal(amount)
                    this.vatAmount = BigDecimal.ZERO
                    this.totalAmount = BigDecimal(amount)
                }
                Payment.new {
                    this.bill = bill
                    this.paymentType = tender
                    this.amount = BigDecimal(amount)
                    this.currency = "EUR"
                    this.paymentStatus = PaymentStatus.COMPLETED
                    this.paymentTime = at
                    this.createsReceivable = tender.createsReceivable
                }
                return bill
            }

            // Session A: a promoted sale, a voucher-tender sale and its Storno.
            val promoted = sale(operatorA, sessionA, "SUM-PROMO", "80.00", T_1000, cash)
            Promotion
                .new {
                    this.name = "Happy Hour"
                    this.description = null
                    this.discountType = DiscountType.FIXED_AMOUNT
                    this.discountValue = BigDecimal("20.00")
                    this.scope = PromotionScope.BILL_WIDE
                    this.targetProduct = null
                    this.isMultiUse = false
                    this.validFrom = T_0900
                    this.validUntil = null
                    this.isActive = true
                    this.createdBy = admin
                    this.createdAt = T_0900
                }.let { promo ->
                    BillPromotion.new {
                        this.bill = promoted
                        this.promotion = promo
                        this.orderItem = null
                        this.deductedAmount = BigDecimal("20.00")
                        this.appliedBy = admin
                        this.appliedAt = T_1000
                    }
                }
            val voucherSale = sale(operatorA, sessionA, "SUM-VOUCHER", "40.00", T_1100, voucherTender)
            voucherSale.deletingReason = "Falsch gebucht"
            val storno =
                Bill.new {
                    this.employeeId = operatorA
                    this.tableSession = sessionA
                    this.billNumber = "SUM-STORNO"
                    this.billAmount = BigDecimal("-40.00")
                    this.paidAmount = BigDecimal("-40.00")
                    this.tips = BigDecimal.ZERO
                    this.startTime = T_1200
                    this.endTime = T_1200
                    this.deletedBill = voucherSale
                    this.deletingReason = "Falsch gebucht"
                    this.stornoReason = StornoReason.ZAHLARTWECHSEL
                }
            Payment.new {
                this.bill = voucherSale
                this.paymentType = voucherTender
                this.amount = BigDecimal("-40.00")
                this.currency = "EUR"
                this.paymentStatus = PaymentStatus.COMPLETED
                this.paymentTime = T_1200
                this.createsReceivable = false
                this.refundedBillId = storno
            }

            // Session B: a sale on account and the clearing bill that settles its invoice.
            val accountBill = sale(operatorB, sessionB, "SUM-ACC", "50.00", T_1030, receivableTender)
            val invoice =
                SalesInvoice.new {
                    this.shop = shop
                    this.revenueOrigin = RevenueOrigin.POS_RECEIVABLE
                    this.status = SalesInvoiceStatus.ISSUED
                    this.buyerName = "Catering Nord GmbH"
                    this.issueDate = T_1030
                    this.serviceDate = T_1030
                    this.billId = accountBill.id
                }
            val clearing =
                Bill.new {
                    this.employeeId = operatorB
                    this.tableSession = sessionB
                    this.billNumber = "SUM-CLR"
                    this.billAmount = BigDecimal("50.00")
                    this.paidAmount = BigDecimal("50.00")
                    this.tips = BigDecimal.ZERO
                    this.startTime = T_1300
                    this.endTime = T_1300
                    this.clearsSalesInvoiceId = invoice.id.value
                }
            Payment.new {
                this.bill = clearing
                this.paymentType = card
                this.amount = BigDecimal("50.00")
                this.currency = "EUR"
                this.paymentStatus = PaymentStatus.COMPLETED
                this.paymentTime = T_1300
                this.createsReceivable = false
            }
        }
    }

    private fun closed(
        tws: TableWithSession,
        startAt: LocalDateTime,
        endAt: LocalDateTime,
    ): TableSession {
        val session = TableSession.findById(tws.session.id)!!
        session.startTime = startAt
        session.endTime = endAt
        session.completed = true
        val table = RTable.findById(tws.table.id)!!
        table.currentSession = null
        table.tableStatus = TableStatus.FREE
        return session
    }

    private fun hrRecord(
        employee: Employee,
        first: String,
        last: String,
    ) {
        val infoId =
            EmployeeInfoTable.insert {
                it[EmployeeInfoTable.employeeId] = employee.id
                it[EmployeeInfoTable.firstName] = first
                it[EmployeeInfoTable.lastName] = last
                it[EmployeeInfoTable.createdAt] = T_0900
                it[EmployeeInfoTable.updatedAt] = T_0900
            }[EmployeeInfoTable.id]
        EmployeeTable.update({ EmployeeTable.id eq employee.id }) { it[EmployeeTable.infoId] = infoId }
    }

    private companion object {
        val T_0900: LocalDateTime = LocalDateTime.parse("2026-03-10T09:00")
        val T_0930: LocalDateTime = LocalDateTime.parse("2026-03-10T09:30")
        val T_1000: LocalDateTime = LocalDateTime.parse("2026-03-10T10:00")
        val T_1030: LocalDateTime = LocalDateTime.parse("2026-03-10T10:30")
        val T_1100: LocalDateTime = LocalDateTime.parse("2026-03-10T11:00")
        val T_1200: LocalDateTime = LocalDateTime.parse("2026-03-10T12:00")
        val T_1300: LocalDateTime = LocalDateTime.parse("2026-03-10T13:00")
        val T_1330: LocalDateTime = LocalDateTime.parse("2026-03-10T13:30")

        const val EXPECTED_BILLS =
            "{\"bills\":[{\"billId\":5,\"billNumber\":\"SUM-CLR\",\"billAmount\":\"50.00\",\"paidAmount\":\"50.00\",\"paymentType\":[{\"amount\":\"50.00\",\"paymentTypeDE\":\"Karte\"}],\"tips\":\"0.00\",\"endTime\":\"2026-03-10T13:00\",\"employeeNumber\":\"TBL_SB\",\"tableNumber\":\"SB\",\"relations\":{\"kind\":\"RECEIVABLE_CLEARING\",\"clearsBill\":{\"billId\":4,\"billNumber\":\"SUM-ACC\"}}},{\"billId\":3,\"billNumber\":\"SUM-STORNO\",\"billAmount\":\"-40.00\",\"paidAmount\":\"-40.00\",\"paymentType\":[{\"amount\":\"-40.00\",\"paymentTypeDE\":\"Gutschein\"}],\"tips\":\"0.00\",\"endTime\":\"2026-03-10T12:00\",\"employeeNumber\":\"TBL_SA\",\"tableNumber\":\"SA\",\"relations\":{\"kind\":\"STORNO\",\"reversesBill\":{\"billId\":2,\"billNumber\":\"SUM-VOUCHER\"},\"stornoReason\":\"ZAHLARTWECHSEL\"}},{\"billId\":2,\"billNumber\":\"SUM-VOUCHER\",\"billAmount\":\"40.00\",\"paidAmount\":\"40.00\",\"paymentType\":[{\"amount\":\"40.00\",\"paymentTypeDE\":\"Gutschein\"},{\"amount\":\"-40.00\",\"paymentTypeDE\":\"Gutschein\"}],\"tips\":\"0.00\",\"endTime\":\"2026-03-10T11:00\",\"employeeNumber\":\"TBL_SA\",\"tableNumber\":\"SA\",\"relations\":{\"isCancelled\":true,\"reversedBy\":{\"billId\":3,\"billNumber\":\"SUM-STORNO\"}}},{\"billId\":4,\"billNumber\":\"SUM-ACC\",\"billAmount\":\"50.00\",\"paidAmount\":\"50.00\",\"paymentType\":[{\"amount\":\"50.00\",\"paymentTypeDE\":\"Rechnung\"}],\"tips\":\"0.00\",\"endTime\":\"2026-03-10T10:30\",\"employeeNumber\":\"TBL_SB\",\"tableNumber\":\"SB\"},{\"billId\":1,\"billNumber\":\"SUM-PROMO\",\"billAmount\":\"80.00\",\"paidAmount\":\"80.00\",\"paymentType\":[{\"amount\":\"80.00\",\"paymentTypeDE\":\"Bar\"}],\"tips\":\"0.00\",\"endTime\":\"2026-03-10T10:00\",\"employeeNumber\":\"TBL_SA\",\"tableNumber\":\"SA\"}],\"totalAmount\":\"180.00\",\"totalTips\":\"0.00\",\"pagination\":{\"currentPage\":0,\"pageSize\":50,\"totalItems\":5,\"totalPages\":1,\"hasNext\":false,\"hasPrevious\":false}}"
        const val EXPECTED_TABLE_SESSIONS =
            "[{\"sessionId\":1,\"startTime\":\"2026-03-10T09:00\",\"endTime\":\"2026-03-10T13:00\",\"revenue\":\"80.00\",\"tips\":\"0.00\",\"billCount\":3,\"bills\":[{\"billId\":1,\"billNumber\":\"SUM-PROMO\",\"billAmount\":\"80.00\",\"paidAmount\":\"80.00\",\"paymentType\":[{\"amount\":\"80.00\",\"paymentTypeDE\":\"Bar\"}],\"tips\":\"0.00\",\"endTime\":\"2026-03-10T10:00\",\"employeeNumber\":\"TBL_SA\",\"tableNumber\":\"SA\"},{\"billId\":2,\"billNumber\":\"SUM-VOUCHER\",\"billAmount\":\"40.00\",\"paidAmount\":\"40.00\",\"paymentType\":[{\"amount\":\"40.00\",\"paymentTypeDE\":\"Gutschein\"},{\"amount\":\"-40.00\",\"paymentTypeDE\":\"Gutschein\"}],\"tips\":\"0.00\",\"endTime\":\"2026-03-10T11:00\",\"employeeNumber\":\"TBL_SA\",\"tableNumber\":\"SA\",\"relations\":{\"isCancelled\":true,\"reversedBy\":{\"billId\":3,\"billNumber\":\"SUM-STORNO\"}}},{\"billId\":3,\"billNumber\":\"SUM-STORNO\",\"billAmount\":\"-40.00\",\"paidAmount\":\"-40.00\",\"paymentType\":[{\"amount\":\"-40.00\",\"paymentTypeDE\":\"Gutschein\"}],\"tips\":\"0.00\",\"endTime\":\"2026-03-10T12:00\",\"employeeNumber\":\"TBL_SA\",\"tableNumber\":\"SA\",\"relations\":{\"kind\":\"STORNO\",\"reversesBill\":{\"billId\":2,\"billNumber\":\"SUM-VOUCHER\"},\"stornoReason\":\"ZAHLARTWECHSEL\"}}]}]"
        const val EXPECTED_GROUPS_PLAIN =
            "{\"employeeGroups\":[{\"employeeNumber\":\"TBL_SA\",\"employeeName\":\"tbl_SA\",\"totalRevenue\":\"80.00\",\"totalTips\":\"0.00\",\"billCount\":1,\"bills\":[{\"billId\":3,\"billNumber\":\"SUM-STORNO\",\"billAmount\":\"-40.00\",\"paidAmount\":\"-40.00\",\"paymentType\":[{\"amount\":\"-40.00\",\"paymentTypeDE\":\"Gutschein\"}],\"tips\":\"0.00\",\"endTime\":\"2026-03-10T12:00\",\"employeeNumber\":\"TBL_SA\",\"tableNumber\":\"SA\",\"relations\":{\"kind\":\"STORNO\",\"reversesBill\":{\"billId\":2,\"billNumber\":\"SUM-VOUCHER\"},\"stornoReason\":\"ZAHLARTWECHSEL\"}},{\"billId\":2,\"billNumber\":\"SUM-VOUCHER\",\"billAmount\":\"40.00\",\"paidAmount\":\"40.00\",\"paymentType\":[{\"amount\":\"40.00\",\"paymentTypeDE\":\"Gutschein\"},{\"amount\":\"-40.00\",\"paymentTypeDE\":\"Gutschein\"}],\"tips\":\"0.00\",\"endTime\":\"2026-03-10T11:00\",\"employeeNumber\":\"TBL_SA\",\"tableNumber\":\"SA\",\"relations\":{\"isCancelled\":true,\"reversedBy\":{\"billId\":3,\"billNumber\":\"SUM-STORNO\"}}},{\"billId\":1,\"billNumber\":\"SUM-PROMO\",\"billAmount\":\"80.00\",\"paidAmount\":\"80.00\",\"paymentType\":[{\"amount\":\"80.00\",\"paymentTypeDE\":\"Bar\"}],\"tips\":\"0.00\",\"endTime\":\"2026-03-10T10:00\",\"employeeNumber\":\"TBL_SA\",\"tableNumber\":\"SA\"}],\"displayName\":\"tbl_SA\"},{\"employeeNumber\":\"TBL_SB\",\"employeeName\":\"tbl_SB\",\"totalRevenue\":\"50.00\",\"totalTips\":\"0.00\",\"billCount\":1,\"bills\":[{\"billId\":5,\"billNumber\":\"SUM-CLR\",\"billAmount\":\"50.00\",\"paidAmount\":\"50.00\",\"paymentType\":[{\"amount\":\"50.00\",\"paymentTypeDE\":\"Karte\"}],\"tips\":\"0.00\",\"endTime\":\"2026-03-10T13:00\",\"employeeNumber\":\"TBL_SB\",\"tableNumber\":\"SB\",\"relations\":{\"kind\":\"RECEIVABLE_CLEARING\",\"clearsBill\":{\"billId\":4,\"billNumber\":\"SUM-ACC\"}}},{\"billId\":4,\"billNumber\":\"SUM-ACC\",\"billAmount\":\"50.00\",\"paidAmount\":\"50.00\",\"paymentType\":[{\"amount\":\"50.00\",\"paymentTypeDE\":\"Rechnung\"}],\"tips\":\"0.00\",\"endTime\":\"2026-03-10T10:30\",\"employeeNumber\":\"TBL_SB\",\"tableNumber\":\"SB\"}],\"displayName\":\"tbl_SB\"}],\"pagination\":{\"currentPage\":0,\"pageSize\":50,\"totalItems\":2,\"totalPages\":1,\"hasNext\":false,\"hasPrevious\":false}}"
        const val EXPECTED_GROUPS_HR =
            "{\"employeeGroups\":[{\"employeeNumber\":\"TBL_SA\",\"employeeName\":\"tbl_SA\",\"totalRevenue\":\"80.00\",\"totalTips\":\"0.00\",\"billCount\":1,\"bills\":[{\"billId\":3,\"billNumber\":\"SUM-STORNO\",\"billAmount\":\"-40.00\",\"paidAmount\":\"-40.00\",\"paymentType\":[{\"amount\":\"-40.00\",\"paymentTypeDE\":\"Gutschein\"}],\"tips\":\"0.00\",\"endTime\":\"2026-03-10T12:00\",\"employeeNumber\":\"TBL_SA\",\"tableNumber\":\"SA\",\"relations\":{\"kind\":\"STORNO\",\"reversesBill\":{\"billId\":2,\"billNumber\":\"SUM-VOUCHER\"},\"stornoReason\":\"ZAHLARTWECHSEL\"}},{\"billId\":2,\"billNumber\":\"SUM-VOUCHER\",\"billAmount\":\"40.00\",\"paidAmount\":\"40.00\",\"paymentType\":[{\"amount\":\"40.00\",\"paymentTypeDE\":\"Gutschein\"},{\"amount\":\"-40.00\",\"paymentTypeDE\":\"Gutschein\"}],\"tips\":\"0.00\",\"endTime\":\"2026-03-10T11:00\",\"employeeNumber\":\"TBL_SA\",\"tableNumber\":\"SA\",\"relations\":{\"isCancelled\":true,\"reversedBy\":{\"billId\":3,\"billNumber\":\"SUM-STORNO\"}}},{\"billId\":1,\"billNumber\":\"SUM-PROMO\",\"billAmount\":\"80.00\",\"paidAmount\":\"80.00\",\"paymentType\":[{\"amount\":\"80.00\",\"paymentTypeDE\":\"Bar\"}],\"tips\":\"0.00\",\"endTime\":\"2026-03-10T10:00\",\"employeeNumber\":\"TBL_SA\",\"tableNumber\":\"SA\"}],\"displayName\":\"Klara Kern\"},{\"employeeNumber\":\"TBL_SB\",\"employeeName\":\"tbl_SB\",\"totalRevenue\":\"50.00\",\"totalTips\":\"0.00\",\"billCount\":1,\"bills\":[{\"billId\":5,\"billNumber\":\"SUM-CLR\",\"billAmount\":\"50.00\",\"paidAmount\":\"50.00\",\"paymentType\":[{\"amount\":\"50.00\",\"paymentTypeDE\":\"Karte\"}],\"tips\":\"0.00\",\"endTime\":\"2026-03-10T13:00\",\"employeeNumber\":\"TBL_SB\",\"tableNumber\":\"SB\",\"relations\":{\"kind\":\"RECEIVABLE_CLEARING\",\"clearsBill\":{\"billId\":4,\"billNumber\":\"SUM-ACC\"}}},{\"billId\":4,\"billNumber\":\"SUM-ACC\",\"billAmount\":\"50.00\",\"paidAmount\":\"50.00\",\"paymentType\":[{\"amount\":\"50.00\",\"paymentTypeDE\":\"Rechnung\"}],\"tips\":\"0.00\",\"endTime\":\"2026-03-10T10:30\",\"employeeNumber\":\"TBL_SB\",\"tableNumber\":\"SB\"}],\"displayName\":\"tbl_SB\"}],\"pagination\":{\"currentPage\":0,\"pageSize\":50,\"totalItems\":2,\"totalPages\":1,\"hasNext\":false,\"hasPrevious\":false}}"
        const val EXPECTED_SESSIONS_PLAIN =
            "{\"sessions\":[{\"id\":2,\"tableNumber\":\"SB\",\"employeeInfo\":{\"employeeNumber\":\"TBL_SB\",\"employeeId\":\"<uuid>\",\"lastName\":\"tbl_SB\"},\"startTime\":\"2026-03-10T09:30\",\"endTime\":\"2026-03-10T13:30\",\"itemCount\":0,\"orderCount\":0,\"durationMinutes\":\"4h\",\"bills\":[{\"billId\":4,\"billNumber\":\"SUM-ACC\",\"billAmount\":\"50.00\",\"paidAmount\":\"50.00\",\"paymentType\":[{\"amount\":\"50.00\",\"paymentTypeDE\":\"Rechnung\"}],\"tips\":\"0.00\",\"endTime\":\"2026-03-10T10:30\",\"employeeNumber\":\"TBL_SB\",\"tableNumber\":\"SB\"},{\"billId\":5,\"billNumber\":\"SUM-CLR\",\"billAmount\":\"50.00\",\"paidAmount\":\"50.00\",\"paymentType\":[{\"amount\":\"50.00\",\"paymentTypeDE\":\"Karte\"}],\"tips\":\"0.00\",\"endTime\":\"2026-03-10T13:00\",\"employeeNumber\":\"TBL_SB\",\"tableNumber\":\"SB\",\"relations\":{\"kind\":\"RECEIVABLE_CLEARING\",\"clearsBill\":{\"billId\":4,\"billNumber\":\"SUM-ACC\"}}}]},{\"id\":1,\"tableNumber\":\"SA\",\"employeeInfo\":{\"employeeNumber\":\"TBL_SA\",\"employeeId\":\"<uuid>\",\"lastName\":\"tbl_SA\"},\"startTime\":\"2026-03-10T09:00\",\"endTime\":\"2026-03-10T13:00\",\"itemCount\":0,\"orderCount\":0,\"durationMinutes\":\"4h\",\"bills\":[{\"billId\":1,\"billNumber\":\"SUM-PROMO\",\"billAmount\":\"80.00\",\"paidAmount\":\"80.00\",\"paymentType\":[{\"amount\":\"80.00\",\"paymentTypeDE\":\"Bar\"}],\"tips\":\"0.00\",\"endTime\":\"2026-03-10T10:00\",\"employeeNumber\":\"TBL_SA\",\"tableNumber\":\"SA\"},{\"billId\":2,\"billNumber\":\"SUM-VOUCHER\",\"billAmount\":\"40.00\",\"paidAmount\":\"40.00\",\"paymentType\":[{\"amount\":\"40.00\",\"paymentTypeDE\":\"Gutschein\"},{\"amount\":\"-40.00\",\"paymentTypeDE\":\"Gutschein\"}],\"tips\":\"0.00\",\"endTime\":\"2026-03-10T11:00\",\"employeeNumber\":\"TBL_SA\",\"tableNumber\":\"SA\",\"relations\":{\"isCancelled\":true,\"reversedBy\":{\"billId\":3,\"billNumber\":\"SUM-STORNO\"}}},{\"billId\":3,\"billNumber\":\"SUM-STORNO\",\"billAmount\":\"-40.00\",\"paidAmount\":\"-40.00\",\"paymentType\":[{\"amount\":\"-40.00\",\"paymentTypeDE\":\"Gutschein\"}],\"tips\":\"0.00\",\"endTime\":\"2026-03-10T12:00\",\"employeeNumber\":\"TBL_SA\",\"tableNumber\":\"SA\",\"relations\":{\"kind\":\"STORNO\",\"reversesBill\":{\"billId\":2,\"billNumber\":\"SUM-VOUCHER\"},\"stornoReason\":\"ZAHLARTWECHSEL\"}}]}],\"pagination\":{\"currentPage\":0,\"pageSize\":50,\"totalItems\":2,\"totalPages\":1,\"hasNext\":false,\"hasPrevious\":false}}"
        const val EXPECTED_SESSIONS_HR =
            "{\"sessions\":[{\"id\":2,\"tableNumber\":\"SB\",\"employeeInfo\":{\"employeeNumber\":\"TBL_SB\",\"employeeId\":\"<uuid>\",\"lastName\":\"tbl_SB\"},\"startTime\":\"2026-03-10T09:30\",\"endTime\":\"2026-03-10T13:30\",\"itemCount\":0,\"orderCount\":0,\"durationMinutes\":\"4h\",\"bills\":[{\"billId\":4,\"billNumber\":\"SUM-ACC\",\"billAmount\":\"50.00\",\"paidAmount\":\"50.00\",\"paymentType\":[{\"amount\":\"50.00\",\"paymentTypeDE\":\"Rechnung\"}],\"tips\":\"0.00\",\"endTime\":\"2026-03-10T10:30\",\"employeeNumber\":\"TBL_SB\",\"tableNumber\":\"SB\"},{\"billId\":5,\"billNumber\":\"SUM-CLR\",\"billAmount\":\"50.00\",\"paidAmount\":\"50.00\",\"paymentType\":[{\"amount\":\"50.00\",\"paymentTypeDE\":\"Karte\"}],\"tips\":\"0.00\",\"endTime\":\"2026-03-10T13:00\",\"employeeNumber\":\"TBL_SB\",\"tableNumber\":\"SB\",\"relations\":{\"kind\":\"RECEIVABLE_CLEARING\",\"clearsBill\":{\"billId\":4,\"billNumber\":\"SUM-ACC\"}}}]},{\"id\":1,\"tableNumber\":\"SA\",\"employeeInfo\":{\"employeeNumber\":\"TBL_SA\",\"employeeId\":\"<uuid>\",\"lastName\":\"Kern\"},\"startTime\":\"2026-03-10T09:00\",\"endTime\":\"2026-03-10T13:00\",\"itemCount\":0,\"orderCount\":0,\"durationMinutes\":\"4h\",\"bills\":[{\"billId\":1,\"billNumber\":\"SUM-PROMO\",\"billAmount\":\"80.00\",\"paidAmount\":\"80.00\",\"paymentType\":[{\"amount\":\"80.00\",\"paymentTypeDE\":\"Bar\"}],\"tips\":\"0.00\",\"endTime\":\"2026-03-10T10:00\",\"employeeNumber\":\"TBL_SA\",\"tableNumber\":\"SA\"},{\"billId\":2,\"billNumber\":\"SUM-VOUCHER\",\"billAmount\":\"40.00\",\"paidAmount\":\"40.00\",\"paymentType\":[{\"amount\":\"40.00\",\"paymentTypeDE\":\"Gutschein\"},{\"amount\":\"-40.00\",\"paymentTypeDE\":\"Gutschein\"}],\"tips\":\"0.00\",\"endTime\":\"2026-03-10T11:00\",\"employeeNumber\":\"TBL_SA\",\"tableNumber\":\"SA\",\"relations\":{\"isCancelled\":true,\"reversedBy\":{\"billId\":3,\"billNumber\":\"SUM-STORNO\"}}},{\"billId\":3,\"billNumber\":\"SUM-STORNO\",\"billAmount\":\"-40.00\",\"paidAmount\":\"-40.00\",\"paymentType\":[{\"amount\":\"-40.00\",\"paymentTypeDE\":\"Gutschein\"}],\"tips\":\"0.00\",\"endTime\":\"2026-03-10T12:00\",\"employeeNumber\":\"TBL_SA\",\"tableNumber\":\"SA\",\"relations\":{\"kind\":\"STORNO\",\"reversesBill\":{\"billId\":2,\"billNumber\":\"SUM-VOUCHER\"},\"stornoReason\":\"ZAHLARTWECHSEL\"}}]}],\"pagination\":{\"currentPage\":0,\"pageSize\":50,\"totalItems\":2,\"totalPages\":1,\"hasNext\":false,\"hasPrevious\":false}}"
    }
}

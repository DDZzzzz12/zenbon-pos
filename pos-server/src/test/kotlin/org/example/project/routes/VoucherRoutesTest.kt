package org.example.project.routes

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.http.content.*
import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.dto.voucher.VoucherDto
import org.example.project.core.domain.enums.bill.PaymentStatus
import org.example.project.core.domain.enums.bill.StornoReason
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.core.domain.enums.voucher.VoucherStatus
import org.example.project.core.domain.models.setup.VoucherSourceType
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.payment.domain.models.Payment
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.voucher.domain.models.Voucher
import org.example.project.features.voucher.domain.models.VoucherRedeem
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.testutils.testJson
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals

@DisplayName("Voucher Routes")
class VoucherRoutesTest {
    /**
     * Seeds three vouchers that pin two server-side fixes with no prior test coverage:
     *
     * 1. `VoucherRepositoryImpl.getVoucherStatistics` (commit d0fc06f74) — `activeVouchers` and
     *    `totalValueRemaining` must both use `status == ACTIVE && fullyRedeemedAt IS NULL`, so an
     *    invalidated-but-never-fully-redeemed voucher doesn't inflate the count without inflating
     *    the value.
     * 2. `VoucherServiceImpl.getVoucherRedemptionHistory` (commit aae26ac65) — `remainingValueAfter`
     *    must be accumulated oldest-first from `originalValue`, not read from the voucher's
     *    (constant) current balance.
     *
     * V1 (50.00, ACTIVE): redeemed 20.00 then 10.00, remaining 20.00 — still ACTIVE, not fully
     * redeemed. V2 (25.00, ACTIVE): fully redeemed (25.00), remaining 0.00, `fullyRedeemedAt` set.
     * V3 (100.00, INVALIDATED): never redeemed, remaining 100.00.
     */
    private fun seedVoucherStatisticsFixture(): VoucherFixture {
        val baseTime = LocalDateTime(2026, 1, 1, 10, 0, 0)
        val redeemedAt1 = baseTime
        val redeemedAt2 = LocalDateTime(2026, 1, 1, 10, 30, 0)
        val fullyRedeemedAt = LocalDateTime(2026, 1, 1, 10, 45, 0)

        val admin = TestData.createAdmin()
        TestData.createShopInfo()
        val vats = TestData.createDefaultVatRates()
        val voucherType = TestData.createPaymentType("Voucher", isCash = false, isVoucher = true)
        val category = TestData.createCategory("Voucher Stats Cat")
        val product = TestData.createProduct(category.id.value, "Voucher Stats Product", "VSP001")
        val tws = TestData.createTableWithSession("VS1")

        transaction {
            val session = TableSession.findById(tws.session.id)!!
            val vat19 = vats[0]

            // The bill the vouchers were sold on. Every real voucher line is reachable from its order
            // (table voucher) or its bill (counter sale); VoucherResponseDto resolves the issuing
            // employee through that link, so a line with neither cannot be listed.
            val issueBill =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = session
                    this.billNumber = "VSTAT-ISSUE"
                    this.billAmount = BigDecimal("175.00")
                    this.paidAmount = BigDecimal("175.00")
                    this.tips = BigDecimal.ZERO
                    this.startTime = baseTime
                    this.endTime = baseTime
                }

            fun newVoucherOrderItem(price: BigDecimal) =
                OrderItem.new {
                    this.session = session
                    this.bill = issueBill
                    this.product = product
                    this.unitPrice = price
                    this.finalPrice = price
                    this.itemStatus = ItemStatus.PAID
                    this.vat = vat19
                    this.takeAway = false
                }

            fun newRedemptionPayment(
                billNumber: String,
                amount: BigDecimal,
                time: LocalDateTime,
            ): Payment {
                val bill =
                    Bill.new {
                        this.employeeId = admin
                        this.tableSession = session
                        this.billNumber = billNumber
                        this.billAmount = amount
                        this.paidAmount = amount
                        this.tips = BigDecimal.ZERO
                        this.startTime = time
                        this.endTime = time
                    }
                return Payment.new {
                    this.bill = bill
                    this.paymentType = voucherType
                    this.amount = amount
                    this.currency = "EUR"
                    this.paymentStatus = PaymentStatus.COMPLETED
                    this.paymentTime = time
                }
            }

            // V1: 50.00 issued, redeemed 20.00 then 10.00 -> remaining 20.00, still ACTIVE.
            val v1 =
                Voucher.new {
                    this.orderItem = newVoucherOrderItem(BigDecimal("50.00"))
                    this.voucherNumber = "VSTAT-V1"
                    this.originalValue = BigDecimal("50.00")
                    this.remainingValue = BigDecimal("20.00")
                    this.createdAt = baseTime
                    this.status = VoucherStatus.ACTIVE
                }
            VoucherRedeem.new {
                this.voucher = v1
                this.payment = newRedemptionPayment("VSTAT-B1", BigDecimal("20.00"), redeemedAt1)
                this.redeemAmount = BigDecimal("20.00")
                this.redeemedAt = redeemedAt1
                this.voucherType = VoucherSourceType.INTERNAL
                this.voucherNumber = "VSTAT-V1"
            }
            VoucherRedeem.new {
                this.voucher = v1
                this.payment = newRedemptionPayment("VSTAT-B2", BigDecimal("10.00"), redeemedAt2)
                this.redeemAmount = BigDecimal("10.00")
                this.redeemedAt = redeemedAt2
                this.voucherType = VoucherSourceType.INTERNAL
                this.voucherNumber = "VSTAT-V1"
            }

            // V2: 25.00 issued, fully redeemed -> remaining 0.00, fullyRedeemedAt set.
            val v2 =
                Voucher.new {
                    this.orderItem = newVoucherOrderItem(BigDecimal("25.00"))
                    this.voucherNumber = "VSTAT-V2"
                    this.originalValue = BigDecimal("25.00")
                    this.remainingValue = BigDecimal("0.00")
                    this.createdAt = baseTime
                    this.status = VoucherStatus.ACTIVE
                    this.fullyRedeemedAt = fullyRedeemedAt
                }
            VoucherRedeem.new {
                this.voucher = v2
                this.payment = newRedemptionPayment("VSTAT-B3", BigDecimal("25.00"), fullyRedeemedAt)
                this.redeemAmount = BigDecimal("25.00")
                this.redeemedAt = fullyRedeemedAt
                this.voucherType = VoucherSourceType.INTERNAL
                this.voucherNumber = "VSTAT-V2"
            }

            // V3: 100.00 issued, invalidated, never redeemed -> remaining 100.00.
            Voucher.new {
                this.orderItem = newVoucherOrderItem(BigDecimal("100.00"))
                this.voucherNumber = "VSTAT-V3"
                this.originalValue = BigDecimal("100.00")
                this.remainingValue = BigDecimal("100.00")
                this.createdAt = baseTime
                this.status = VoucherStatus.INVALIDATED
            }
        }

        return VoucherFixture(voucherNumber = "VSTAT-V1", redeemedAt1 = redeemedAt1, redeemedAt2 = redeemedAt2)
    }

    private data class VoucherFixture(
        val voucherNumber: String,
        val redeemedAt1: LocalDateTime,
        val redeemedAt2: LocalDateTime,
    )

    @Nested
    @DisplayName("GET /api/vouchers/statistics")
    inner class GetStatistics {
        @Test
        fun `returns 200 with statistics`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/vouchers/statistics") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, response.status)
            }

        @Test
        fun `active count and remaining value share the same ACTIVE and not-fully-redeemed predicate`() =
            testApp(
                setupData = { seedVoucherStatisticsFixture() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/vouchers/statistics") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, response.status, "body: ${response.bodyAsText()}")

                val dto = testJson.decodeFromString<VoucherDto.VoucherStatisticsDto>(response.bodyAsText())

                assertEquals(3L, dto.totalVouchers, "totalVouchers")
                // V1 only: V2 is fully redeemed, V3 is invalidated. Without the fix this would be 2
                // (V1 + V3), because `fullyRedeemedAt IS NULL` alone doesn't exclude INVALIDATED.
                assertEquals(1L, dto.activeVouchers, "activeVouchers")
                assertEquals(1L, dto.fullyRedeemedVouchers, "fullyRedeemedVouchers")
                assertEquals(1L, dto.invalidatedVouchers, "invalidatedVouchers")
                assertEquals("175.00", dto.totalValueIssued, "totalValueIssued")
                // V1 only (20.00) — the value side of the same predicate.
                assertEquals("20.00", dto.totalValueRemaining, "totalValueRemaining")
                assertEquals("100.00", dto.totalValueInvalidated, "totalValueInvalidated")
                // Derived as issued - remaining - invalidated = 175 - 20 - 100 = 55, which
                // independently equals the actual redemptions: 20.00 + 10.00 + 25.00 = 55.00.
                assertEquals("55.00", dto.totalValueRedeemed, "totalValueRedeemed")
                // Resolved server-side from ConfigKey.DEFAULT_CURRENCY (IShopConfigurationManager),
                // not a client-side constant — see VoucherServiceImpl.getVoucherStatistics.
                assertEquals("EUR", dto.currency, "currency")
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/vouchers/statistics")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    /**
     * T5-2: `747923678` dropped both routes as "no client caller" while the web back office's
     * Gutscheine screen (`KtorVoucherApiClient`) was calling them, so its "Aktive Gutscheine" tab and
     * number search failed. Pinned end to end here so a future spec clean-up cannot drop them again.
     */
    @Nested
    @DisplayName("GET /api/vouchers/active and /search")
    inner class ActiveAndSearch {
        @Test
        fun `active lists only vouchers still carrying a balance`() =
            testApp(
                setupData = { seedVoucherStatisticsFixture() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/vouchers/active?limit=50&offset=0") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, response.status, "body: ${response.bodyAsText()}")

                val numbers =
                    testJson
                        .decodeFromString<List<VoucherDto.VoucherResponseDto>>(response.bodyAsText())
                        .map { it.voucherNumber }
                // V2 is fully redeemed, V3 invalidated: only V1 (20.00 left) is outstanding.
                assertEquals(listOf("VSTAT-V1"), numbers)
            }

        @Test
        fun `search matches the voucher number case-insensitively`() =
            testApp(
                setupData = { seedVoucherStatisticsFixture() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/vouchers/search?q=vstat-v3&limit=50&offset=0") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, response.status, "body: ${response.bodyAsText()}")

                val numbers =
                    testJson
                        .decodeFromString<List<VoucherDto.VoucherResponseDto>>(response.bodyAsText())
                        .map { it.voucherNumber }
                assertEquals(listOf("VSTAT-V3"), numbers, "search is not limited to active vouchers")
            }

        @Test
        fun `search without a term is refused with 400`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                assertEquals(HttpStatusCode.BadRequest, client.get("/api/vouchers/search") { bearerAuth(token) }.status)
                assertEquals(HttpStatusCode.BadRequest, client.get("/api/vouchers/search?q=%20") { bearerAuth(token) }.status)
            }

        @Test
        fun `a terminal device is refused the shop-wide list`() =
            testApp(
                setupData = {
                    TestData.createAdmin()
                    TestData.createTerminal()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsTerminal(client)
                assertEquals(HttpStatusCode.Forbidden, client.get("/api/vouchers/active") { bearerAuth(token) }.status)
                assertEquals(HttpStatusCode.Forbidden, client.get("/api/vouchers/search?q=V") { bearerAuth(token) }.status)
                // Control: the same principal is not refused a VOUCHER_READ route, so the 403 above is the
                // VOUCHER_REPORT gate and not a principal that reaches nothing.
                val control = client.get("/api/vouchers/number/NOPE/redemption-history") { bearerAuth(token) }
                kotlin.test.assertNotEquals(HttpStatusCode.Forbidden, control.status, "body: ${control.bodyAsText()}")
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                assertEquals(HttpStatusCode.Unauthorized, client.get("/api/vouchers/active").status)
                assertEquals(HttpStatusCode.Unauthorized, client.get("/api/vouchers/search?q=V").status)
            }
    }

    @Nested
    @DisplayName("GET /api/vouchers/created-by-date")
    inner class GetCreatedByDate {
        @Test
        fun `returns 400 when dates missing`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/vouchers/created-by-date") { bearerAuth(token) }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 400 when startDate missing`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/vouchers/created-by-date?endDate=2026-12-31") { bearerAuth(token) }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 400 when endDate missing`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/vouchers/created-by-date?startDate=2026-01-01") { bearerAuth(token) }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 200 with valid date range`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get(
                        "/api/vouchers/created-by-date?startDate=2026-01-01T00:00:00&endDate=2026-12-31T23:59:59",
                    ) { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/vouchers/created-by-date?startDate=2026-01-01T00:00:00&endDate=2026-12-31T23:59:59")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("GET /api/vouchers/redeemed-by-date")
    inner class GetRedeemedByDate {
        /**
         * admin20 FAIL-B20: the Gutscheine tiles counted redemptions a Zahlartwechsel had reversed,
         * so they disagreed with the Z-Bon. The list still carries the reversed row — it happened —
         * but flags it with the Storno bill and its reason, and `stands` is false for it only.
         */
        @Test
        fun `a reversed redemption is listed with its Storno and does not stand`() =
            testApp(
                setupData = {
                    seedVoucherStatisticsFixture()
                    transaction {
                        val redeem = VoucherRedeem.all().single { it.payment.bill.billNumber == "VSTAT-B2" }
                        val original = redeem.payment.bill
                        redeem.reversedByBill =
                            Bill.new {
                                this.employeeId = original.employeeId
                                this.tableSession = original.tableSession
                                this.billNumber = "VSTAT-B2-STORNO"
                                this.billAmount = original.billAmount.negate()
                                this.paidAmount = original.paidAmount.negate()
                                this.tips = BigDecimal.ZERO
                                this.startTime = original.startTime
                                this.endTime = original.endTime
                                this.deletedBill = original
                                this.stornoReason = StornoReason.ZAHLARTWECHSEL
                            }
                    }
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get(
                        "/api/vouchers/redeemed-by-date?startDate=2026-01-01T00:00:00&endDate=2026-12-31T23:59:59",
                    ) { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, response.status, "body: ${response.bodyAsText()}")

                val rows = testJson.decodeFromString<List<VoucherDto.VoucherRedemptionSummaryDto>>(response.bodyAsText())
                assertEquals(3, rows.size, "the reversed redemption is still listed")
                val reversed = rows.single { it.billNumber == "VSTAT-B2" }
                assertEquals("VSTAT-B2-STORNO", reversed.reversedByBillNumber)
                assertEquals(StornoReason.ZAHLARTWECHSEL, reversed.reversalReason)
                assertEquals(false, reversed.stands)
                assertEquals(
                    listOf("VSTAT-B1", "VSTAT-B3"),
                    rows.filter { it.stands }.map { it.billNumber }.sorted(),
                    "only the untouched redemptions stand",
                )
                assertEquals(
                    BigDecimal("45.00"),
                    rows.filter { it.stands }.sumOf { BigDecimal(it.redeemedAmount) },
                    "20.00 + 25.00 — the reversed 10.00 does not count, as on the Z-Bon",
                )
            }

        @Test
        fun `returns 400 when dates missing`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/vouchers/redeemed-by-date") { bearerAuth(token) }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 200 with valid date range`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get(
                        "/api/vouchers/redeemed-by-date?startDate=2026-01-01T00:00:00&endDate=2026-12-31T23:59:59",
                    ) { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/vouchers/redeemed-by-date?startDate=2026-01-01T00:00:00&endDate=2026-12-31T23:59:59")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("POST /api/vouchers")
    inner class CreateVoucher {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response =
                    client.post("/api/vouchers") {
                        setBody(TextContent("{}", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        @Test
        fun `returns 400 for invalid request`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.post("/api/vouchers") {
                        bearerAuth(token)
                        setBody(TextContent("not json", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }
    }

    @Nested
    @DisplayName("GET /api/vouchers/number/{voucherNumber}/redemption-history")
    inner class GetRedemptionHistory {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/vouchers/number/V001/redemption-history")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        @Test
        fun `remainingValueAfter is accumulated oldest-first from originalValue`() {
            lateinit var fixture: VoucherFixture
            testApp(
                setupData = { fixture = seedVoucherStatisticsFixture() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/vouchers/number/${fixture.voucherNumber}/redemption-history") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.OK, response.status, "body: ${response.bodyAsText()}")

                val dto = testJson.decodeFromString<VoucherDto.VoucherRedemptionHistoryDto>(response.bodyAsText())
                assertEquals(fixture.voucherNumber, dto.voucherNumber)
                assertEquals(2, dto.redemptions.size, "redemptions.size")

                // Repository returns redeemedAt DESC — newest (10.00 @ redeemedAt2) first, then the
                // 20.00 @ redeemedAt1. The old broken code read the constant current balance for
                // every row (40.00 / 30.00); the fix accumulates oldest-first from originalValue.
                val newest = dto.redemptions[0]
                assertEquals("10.00", newest.redeemedAmount, "newest.redeemedAmount")
                assertEquals("20.00", newest.remainingValueAfter, "newest.remainingValueAfter")
                assertEquals("VSTAT-B2", newest.billNumber, "newest.billNumber")
                assertEquals("ADM001", newest.employeeNumber, "newest.employeeNumber")

                val oldest = dto.redemptions[1]
                assertEquals("20.00", oldest.redeemedAmount, "oldest.redeemedAmount")
                assertEquals("30.00", oldest.remainingValueAfter, "oldest.remainingValueAfter")
                assertEquals("VSTAT-B1", oldest.billNumber, "oldest.billNumber")
                assertEquals("ADM001", oldest.employeeNumber, "oldest.employeeNumber")
            }
        }
    }

    @Nested
    @DisplayName("POST /api/vouchers/validate")
    inner class ValidateVoucher {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response =
                    client.post("/api/vouchers/validate") {
                        setBody(TextContent("{}", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        @Test
        fun `returns 400 for invalid request`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.post("/api/vouchers/validate") {
                        bearerAuth(token)
                        setBody(TextContent("not json", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }
    }
}

package org.example.project.features.bill

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.example.project.core.domain.enums.order.OrderStatus
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.bill.mapper.getFirstOrderTseStartTime
import org.example.project.features.order.domain.models.Orders
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.tse.domain.models.TseTransaction
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.example.project.utils.builder.tse.toQrCodeDataDto
import org.example.project.utils.formatter.TimeProvider
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import java.util.TimeZone as JavaTimeZone

/**
 * STAM-2/TSER-10 (round 3), latent-bug guard the export-side test could not catch:
 * `TseTransaction.startTime`/`endTime` are shop-local wall times written via `currentTimeStamp()`
 * → `TimezoneConfig.getApplicationTimeZone()` (the SHOP zone, NOT the JVM default). The server-clock
 * fallbacks in `getFirstOrderTseStartTime` and `toQrCodeDataDto` must decode them with the SHOP zone
 * (`getTimeZone()`), not `TimeZone.currentSystemDefault()`.
 *
 * This test forces the two zones APART: JVM default = Asia/Tokyo (UTC+9), shop zone = Europe/Berlin
 * (UTC+2 in July). A 14:00 shop-local wall time resolves to 12:00Z under Berlin but 05:00Z under
 * Tokyo — the assertions pin the Berlin instant and reject the Tokyo one. With the old
 * `currentSystemDefault()` this went red (resolved via Tokyo).
 */
@DisplayName("Bill TSE server-clock fallback decodes with the shop zone, not the JVM default (STAM-2)")
class BillMapperTseFallbackZoneTest {
    private val berlin = TimeZone.of("Europe/Berlin")
    private val tokyo = TimeZone.of("Asia/Tokyo")
    private val wall = LocalDateTime(2026, 7, 15, 14, 0, 0) // shop-local start
    private val wallEnd = LocalDateTime(2026, 7, 15, 15, 0, 0) // shop-local end

    private var savedJvmDefault: JavaTimeZone? = null

    @BeforeEach
    fun setup() {
        savedJvmDefault = JavaTimeZone.getDefault()
        // JVM default deliberately DIFFERENT from the shop zone, so currentSystemDefault() != getTimeZone().
        JavaTimeZone.setDefault(JavaTimeZone.getTimeZone("Asia/Tokyo"))
        TestDatabase.setup()
        // Shop zone = Europe/Berlin (the zone currentTimeStamp() would write startTime/endTime in).
        org.example.project.utils.formatter.registerTimeProvider(
            object : TimeProvider {
                // now() is irrelevant here — startTime/endTime are set to explicit wall times below.
                override fun now(): LocalDateTime = wall

                override fun getTimeZone(): TimeZone = berlin
            },
        )
    }

    @AfterEach
    fun teardown() {
        org.example.project.utils.formatter
            .registerTimeProvider(TimeProvider.SYSTEM_DEFAULT)
        TestDatabase.teardown()
        savedJvmDefault?.let { JavaTimeZone.setDefault(it) }
    }

    private fun seedBillWithFirstOrder(): Long =
        transaction {
            val tws = TestData.createTableWithSession("FBZ-1")
            val session = TableSession.findById(tws.session.id)!!
            val employee = session.employeeId

            val orderTse =
                TseTransaction.new {
                    this.transactionNumber = 42L
                    this.signatureCounter = 7L
                    this.signature = "c2ln"
                    this.signatureAlgorithm = "ecdsa-plain-SHA256"
                    this.publicKey = "cHVibGljLWtleQ=="
                    this.tseSerialNumber = "AB0912CD".repeat(8)
                    this.clientId = "POS-1"
                    this.processType = "Bestellung-V1"
                    this.processDataHash = "hash"
                    this.startTime = wall
                    this.endTime = wallEnd
                    this.tseStartTimestamp = null // force the server-clock fallback
                    this.tseTimestamp = null // force the server-clock fallback
                    this.tseMode = TseMode.NORMAL
                }

            Orders.new {
                this.orderNumber = "ORD-FBZ-1"
                this.employee = employee
                this.tableSession = session
                this.orderTime = wall
                this.orderTotal = BigDecimal("10.0000")
                this.orderStatus = OrderStatus.CONFIRMED
                this.tseTransaction = orderTse
            }

            Bill
                .new {
                    this.employeeId = employee
                    this.tableSession = session
                    this.billNumber = "BILL-FBZ-1"
                    this.billAmount = BigDecimal("10.0000")
                    this.paidAmount = BigDecimal("10.0000")
                    this.startTime = wall
                    this.endTime = wallEnd
                }.id.value
        }

    @Test
    fun `getFirstOrderTseStartTime and QR fallback resolve via the shop zone, not the JVM default`() {
        val berlinStartEpoch = wall.toInstant(berlin).epochSeconds
        val tokyoStartEpoch = wall.toInstant(tokyo).epochSeconds
        val berlinEndEpoch = wallEnd.toInstant(berlin).epochSeconds
        // Sanity: the two zones genuinely diverge (7h offset difference in July).
        assertNotEquals(berlinStartEpoch, tokyoStartEpoch)

        val billId = seedBillWithFirstOrder()

        transaction {
            val bill = Bill.findById(billId)!!

            assertEquals(
                berlinStartEpoch,
                bill.getFirstOrderTseStartTime(),
                "getFirstOrderTseStartTime must decode the shop-local startTime in the SHOP zone (Berlin)",
            )
            assertNotEquals(
                tokyoStartEpoch,
                bill.getFirstOrderTseStartTime(),
                "must NOT decode via the JVM default (Tokyo) — that was the latent STAM-2 bug",
            )

            val firstOrderTse =
                bill.tableSession.orders
                    .filter { it.tseTransaction != null }
                    .minByOrNull { it.orderTime }!!
                    .tseTransaction!!
            val qr = firstOrderTse.toQrCodeDataDto()!!

            assertEquals(
                berlinStartEpoch,
                qr.startTimeSeconds,
                "QR start-time fallback must decode startTime in the SHOP zone (Berlin)",
            )
            assertNotEquals(
                tokyoStartEpoch,
                qr.startTimeSeconds,
                "QR start-time fallback must NOT decode via the JVM default (Tokyo)",
            )
            assertEquals(
                berlinEndEpoch,
                qr.logTimeSeconds,
                "QR log-time fallback must decode endTime in the SHOP zone (Berlin)",
            )
        }
    }
}

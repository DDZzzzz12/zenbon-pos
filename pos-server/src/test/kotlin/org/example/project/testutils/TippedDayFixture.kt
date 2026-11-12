package org.example.project.testutils

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.payment.PaymentDto
import org.example.project.core.domain.dto.report.ZBonDto
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.core.domain.enums.rTable.TableStatus
import org.example.project.core.domain.enums.zBon.TipPayoutMethod
import org.example.project.features.bill.domain.service.BillService
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.table.domain.models.RTable
import org.example.project.features.table.domain.models.TableSession
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert
import org.koin.core.context.GlobalContext
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.assertEquals

/**
 * A day with employee tips (TrinkgeldAN), closed by the real services: bills through [BillService],
 * the closing through `POST /api/z-bon` — so a test sees exactly what production writes.
 *
 * Seeds an `admin` (closes the day and pays out), a `cashier` (no `CASHBOOK_WRITE`) and one waiter
 * per [Sale.waiter] key, a 19 % product at 20.00, a cash and a card tender and a fiscal registered
 * terminal on [deviceId]. Call [seed] from `setupData`, then [close] inside the test.
 */
class TippedDayFixture(
    private val deviceId: String,
    private val prefix: String,
) {
    /** One bill: a 20.00 item closed by [waiter] with [tip], paid entirely by cash or entirely by card. */
    data class Sale(
        val waiter: String,
        val tip: String,
        val byCard: Boolean = false,
    )

    /** waiter key -> employee UUID. */
    val waiters = mutableMapOf<String, UUID>()
    var cardPaymentTypeId: Long = 0L
        private set

    fun seed(sales: List<Sale>) {
        TestData.createShopInfo()
        TestData.createAdmin(employeeNumber = "${prefix}_ADM", username = "admin")
        TestData.createCashier(employeeNumber = "${prefix}_CSH", username = "cashier")
        sales.map { it.waiter }.distinct().forEachIndexed { i, key ->
            waiters[key] = TestData.createManager(employeeNumber = "${prefix}_W$i", username = "${prefix.lowercase()}_$key").id.value
        }
        val vat19 = TestData.createDefaultVatRates().first()
        val cash = TestData.createPaymentType("Cash_$prefix", isCash = true)
        val card = TestData.createPaymentType("Card_$prefix", isCash = false)
        cardPaymentTypeId = card.id.value
        val category = TestData.createCategory("$prefix Cat")
        val product = TestData.createProduct(category.id.value, "$prefix Product", "${prefix}_P", BigDecimal("20.0000"))
        TestData.createFiscalRegisteredTerminal(deviceId = deviceId, clientId = "TERM-$prefix")
        transaction {
            // H2 stand-in for the Postgres advisory lock used to serialize Kassenbuch/Z-Bon writes.
            // Postgres has the real function (and no CREATE ALIAS).
            if (db.vendor == "h2") exec("CREATE ALIAS IF NOT EXISTS PG_ADVISORY_XACT_LOCK AS 'long pgAdvisoryXactLock(long key) { return key; }'")
        }

        sales.forEachIndexed { i, sale ->
            val tws = TestData.createTableWithSession("${prefix}T$i")
            val waiterId = waiters.getValue(sale.waiter)
            val itemId =
                transaction {
                    val session = TableSession.findById(tws.session.id)!!
                    session.employeeId =
                        org.example.project.features.employee.domain.models.Employee
                            .findById(waiterId)!!
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
            val paid = BigDecimal("20.00").add(BigDecimal(sale.tip)).toPlainString()
            runBlocking {
                GlobalContext.get().get<BillService>().createBill(
                    employeeId = waiterId.toString(),
                    deviceId = deviceId,
                    request =
                        BillDto.CreateBillRequestDto(
                            tableSessionId = tws.session.id.value,
                            orderItems = listOf(itemId),
                            billAmount = "20.00",
                            tips = sale.tip,
                            payments =
                                listOf(
                                    PaymentDto.PaymentRequest(
                                        amount = paid,
                                        paymentTypeId = if (sale.byCard) card.id.value else cash.id.value,
                                    ),
                                ),
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
    }

    /** Closes the day as `admin` and returns the created closing. */
    suspend fun close(client: HttpClient): ZBonDto.ZBonResponseDto {
        val token = TestAuthHelper.loginAs(client, "admin", deviceId = deviceId)
        val response = client.post("/api/z-bon") { bearerAuth(token) }
        assertEquals(HttpStatusCode.Created, response.status, "closing failed: ${response.bodyAsText()}")
        return testJson.decodeFromString(ZBonDto.ZBonResponseDto.serializer(), response.bodyAsText())
    }

    /** `POST /api/z-bon` with [request] as [username] — the closing dialog's call, payouts included (F58). */
    suspend fun closeWith(
        client: HttpClient,
        request: ZBonDto.CreateZBonRequestDto,
        username: String = "admin",
    ): HttpResponse {
        val token = TestAuthHelper.loginAs(client, username, deviceId = deviceId)
        return client.post("/api/z-bon") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }

    /** `GET /api/z-bon/open-period-tips` as [username]. */
    suspend fun openPeriodTips(
        client: HttpClient,
        username: String = "admin",
    ): HttpResponse {
        val token = TestAuthHelper.loginAs(client, username, deviceId = deviceId)
        return client.get("/api/z-bon/open-period-tips") { bearerAuth(token) }
    }

    /**
     * A non-fiscal Tresor configured as the daily Geldtransit target, so the closing moves its cash
     * takings to the safe. Call from `setupData` after [seed]. Returns the Tresor's id.
     */
    fun routeTakingsToSafe(): Long {
        val tresorId =
            transaction {
                val shop =
                    org.example.project.features.setup.domain.models.ShopInfo
                        .all()
                        .first()
                val tresor =
                    org.example.project.features.kassenbuch.data.repository
                        .CashBookRepositoryImpl()
                        .create(shop, "Tresor", isFiscal = false, datevAccount = "1010")
                org.example.project.features.setup.database.tables.ShopConfigurationTable.upsert {
                    it[org.example.project.features.setup.database.tables.ShopConfigurationTable.shopId] = shop.id.value
                    it[org.example.project.features.setup.database.tables.ShopConfigurationTable.key] =
                        org.example.project.features.setup.database.tables.ConfigKey.CASH_BOOK_TRANSFER_TARGET.key
                    it[org.example.project.features.setup.database.tables.ShopConfigurationTable.value] =
                        kotlinx.serialization.json.JsonPrimitive(tresor.id.value.toString())
                    it[org.example.project.features.setup.database.tables.ShopConfigurationTable.description] = "test"
                    it[org.example.project.features.setup.database.tables.ShopConfigurationTable.updatedAt] = null
                }
                tresor.id.value
            }
        runBlocking {
            org.example.project.core.config.ShopConfigurationManager
                .refreshConfiguration()
        }
        return tresorId
    }

    /** Undoes [routeTakingsToSafe] so the process-wide config cache does not leak into the next test. */
    fun stopRoutingToSafe() {
        transaction {
            org.example.project.features.setup.database.tables.ShopConfigurationTable.deleteWhere {
                org.example.project.features.setup.database.tables.ShopConfigurationTable.key eq
                    org.example.project.features.setup.database.tables.ConfigKey.CASH_BOOK_TRANSFER_TARGET.key
            }
        }
        runBlocking {
            org.example.project.core.config.ShopConfigurationManager
                .refreshConfiguration()
        }
    }

    /** A table left with an un-billed OPEN item — what refuses a closing with `ZBON_TABLES_OPEN`. */
    fun leaveTableOpen() {
        val tws = TestData.createTableWithSession("${prefix}OPEN")
        transaction {
            val session = TableSession.findById(tws.session.id)!!
            val product =
                org.example.project.features.product.domain.models.Product
                    .all()
                    .first()
            OrderItem.new {
                this.session = session
                this.product = product
                this.unitPrice = BigDecimal("20.00")
                this.finalPrice = BigDecimal("20.00")
                this.itemStatus = ItemStatus.OPEN
                this.vat =
                    org.example.project.features.payment.domain.models.Vat
                        .all()
                        .first()
                this.discount = BigDecimal.ONE
                this.customPrice = false
                this.takeAway = false
                this.transferred = false
                this.sideNotes = null
            }
        }
    }

    /** `POST /api/z-bon/{zBonId}/tip-payouts` as [username]. */
    suspend fun payOut(
        client: HttpClient,
        zBonId: Long,
        employeeId: String,
        amount: String,
        username: String = "admin",
        method: TipPayoutMethod = TipPayoutMethod.CASH,
    ): HttpResponse {
        val token = TestAuthHelper.loginAs(client, username, deviceId = deviceId)
        return client.post("/api/z-bon/$zBonId/tip-payouts") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(ZBonDto.CreateTipPayoutRequestDto(employeeId = employeeId, amount = amount, method = method))
        }
    }

    /** `POST /api/z-bon/{zBonId}/tip-payouts/settlements/{payoutId}/reverse` as [username] (F59). */
    suspend fun reverseSettlement(
        client: HttpClient,
        zBonId: Long,
        payoutId: Long,
        reason: String = "doch bar gezahlt",
        username: String = "admin",
    ): HttpResponse {
        val token = TestAuthHelper.loginAs(client, username, deviceId = deviceId)
        return client.post("/api/z-bon/$zBonId/tip-payouts/settlements/$payoutId/reverse") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(ZBonDto.ReverseTipPayoutRequestDto(reason = reason))
        }
    }

    /** A non-fiscal, active cash book of the shop — NOT the transfer target. Returns its id. */
    fun createBook(name: String): Long =
        transaction {
            val shop =
                org.example.project.features.setup.domain.models.ShopInfo
                    .all()
                    .first()
            org.example.project.features.kassenbuch.data.repository
                .CashBookRepositoryImpl()
                .create(shop, name, isFiscal = false, datevAccount = "1010")
                .id.value
        }

    /**
     * Funds cash book [cashBookId] (null = the fiscal Ladenkasse) with [amount] — a plain deposit
     * written straight through the repository, so it costs no document, terminal or signature. The
     * Kassenminus guard (F59) refuses outflows from an unfunded book; tests that pay out cash put it
     * there first.
     */
    fun fund(
        cashBookId: Long?,
        amount: String,
    ) = TestData.fundCashBook(amount, cashBookId)

    /** `POST /api/z-bon/{zBonId}/tip-payouts/{payoutHeaderId}/reverse` as [username]. */
    suspend fun reverse(
        client: HttpClient,
        zBonId: Long,
        payoutHeaderId: Long,
        reason: String = "falscher Mitarbeiter",
        username: String = "admin",
    ): HttpResponse {
        val token = TestAuthHelper.loginAs(client, username, deviceId = deviceId)
        return client.post("/api/z-bon/$zBonId/tip-payouts/$payoutHeaderId/reverse") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(ZBonDto.ReverseTipPayoutRequestDto(reason = reason))
        }
    }
}

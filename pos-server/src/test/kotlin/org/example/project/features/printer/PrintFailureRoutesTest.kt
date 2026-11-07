package org.example.project.features.printer

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.order.OrderDto
import org.example.project.core.domain.dto.order.OrderItemDto
import org.example.project.core.domain.dto.printer.PrinterDto
import org.example.project.core.domain.dto.setup.ShopHeaderData
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.error.PosErrorCode
import org.example.project.core.error.ProblemDetail
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.printer.domain.service.PrinterService
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.testutils.testJson
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A printer that reports failure must surface as 502 `PRINTER_UNAVAILABLE` from the Z-Bon print
 * (`POST /api/z-bon/{id}/print`) and the reference print (`POST /api/orders/reference-print`), the
 * way the bill print does, instead of a silent 200. A successful print keeps answering 200. Only
 * the printer hardware is replaced; routes, services and the database are the real ones.
 */
@DisplayName("Z-Bon and reference print failures reach the caller")
class PrintFailureRoutesTest {
    /** Prints nothing; answers with the configured outcome. */
    private class StubPrinter(
        private val success: Boolean,
    ) : PrinterService {
        private fun outcome() =
            PrinterDto.PrintResponseDto(
                success = success,
                message = if (success) "recorded" else "paper out",
                timestamp = "now",
            )

        override suspend fun printBill(
            bill: BillDto.BillResponseDto,
            employee: Employee,
            printerId: Long?,
            bewirtungsbeleg: Boolean,
        ) = error("not printed in this test")

        override suspend fun printOrder(
            order: OrderDto.OrderResponseDto,
            printerId: Long?,
            language: Language?,
            takeAway: Boolean,
        ) = error("not printed in this test")

        override suspend fun printZBon(
            format: suspend () -> ByteArray,
            printerId: Long?,
        ) = outcome()

        override suspend fun printReferencePrint(
            shopHeader: ShopHeaderData,
            tableNumber: String,
            employeeNumber: String,
            dateTime: String,
            items: List<OrderItemDto.OrderItemResponseDto>,
            printerId: Long?,
        ) = outcome()

        override suspend fun testPrint(
            printerLocationId: Long,
            printedBy: String,
        ) = error("not printed in this test")
    }

    private fun useStubPrinter(success: Boolean) = loadKoinModules(module { single<PrinterService> { StubPrinter(success) } })

    private suspend fun HttpClient.setPrinting(
        adminToken: String,
        enabled: Boolean,
    ) {
        val response =
            put("/api/v1/config/1/print-settings") {
                bearerAuth(adminToken)
                setBody(TextContent("""{"enabled": $enabled}""", ContentType.Application.Json))
            }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
    }

    private suspend fun assertPrinterUnavailable(response: HttpResponse) {
        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.BadGateway, response.status, body)
        assertEquals(PosErrorCode.PRINTER_UNAVAILABLE.name, testJson.decodeFromString<ProblemDetail>(body).code, body)
        assertTrue(body.contains("paper out"), "the printer's reason reaches the caller: $body")
    }

    private fun seedZBon(): Long {
        val admin = TestData.createAdmin()
        val shop = TestData.createShopInfo()
        return TestData.createZBonFullDay(shop, admin).id.value
    }

    @Test
    fun `a failing printer makes the Z-Bon print answer 502 PRINTER_UNAVAILABLE`() {
        var zbonId = 0L
        testApp(
            setupData = {
                zbonId = seedZBon()
                useStubPrinter(success = false)
            },
        ) { client ->
            val admin = TestAuthHelper.loginAsAdmin(client)
            assertPrinterUnavailable(client.post("/api/z-bon/$zbonId/print") { bearerAuth(admin) })
        }
    }

    @Test
    fun `a successful Z-Bon print still answers 200 with the printer's outcome`() {
        var zbonId = 0L
        testApp(
            setupData = {
                zbonId = seedZBon()
                useStubPrinter(success = true)
            },
        ) { client ->
            val admin = TestAuthHelper.loginAsAdmin(client)
            val response = client.post("/api/z-bon/$zbonId/print") { bearerAuth(admin) }
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            assertTrue(response.bodyAsText().contains("recorded"))
        }
    }

    private fun seedTableWithItem(): Pair<Long, Long> {
        TestData.createAdmin()
        TestData.createShopInfo()
        val fixture = TestData.createTableWithSession("REF")
        val item = TestData.createOrderItemInSession(fixture.session, ItemStatus.OPEN)
        return fixture.table.id.value to item.id.value
    }

    private suspend fun HttpClient.referencePrint(
        token: String,
        tableId: Long,
        itemId: Long,
    ): HttpResponse =
        post("/api/orders/reference-print") {
            bearerAuth(token)
            setBody(TextContent("""{"tableId": $tableId, "orderItemIds": [$itemId]}""", ContentType.Application.Json))
        }

    @Test
    fun `a failing printer makes the reference print answer 502 PRINTER_UNAVAILABLE`() {
        var ids = 0L to 0L
        testApp(
            setupData = {
                ids = seedTableWithItem()
                useStubPrinter(success = false)
            },
        ) { client ->
            val admin = TestAuthHelper.loginAsAdmin(client)
            client.setPrinting(admin, enabled = true)
            try {
                assertPrinterUnavailable(client.referencePrint(admin, ids.first, ids.second))
            } finally {
                client.setPrinting(admin, enabled = false)
            }
        }
    }

    @Test
    fun `a successful reference print still answers 200`() {
        var ids = 0L to 0L
        testApp(
            setupData = {
                ids = seedTableWithItem()
                useStubPrinter(success = true)
            },
        ) { client ->
            val admin = TestAuthHelper.loginAsAdmin(client)
            client.setPrinting(admin, enabled = true)
            try {
                val response = client.referencePrint(admin, ids.first, ids.second)
                assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            } finally {
                client.setPrinting(admin, enabled = false)
            }
        }
    }
}

package org.example.project.features.printer

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.order.OrderDto
import org.example.project.core.domain.dto.order.OrderItemDto
import org.example.project.core.domain.dto.printer.PrinterDto
import org.example.project.core.domain.dto.setup.ShopHeaderData
import org.example.project.core.domain.enums.setup.Language
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.printer.domain.service.PrinterService
import org.example.project.features.zBon.domain.service.ZBonService
import org.example.project.printer.core.formatter.ZBonFormatter
import org.example.project.testutils.SourceRoots
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `POST /api/z-bon/{id}/print` formats the Z-Bon itself and hands [PrinterService.printZBon] only
 * the byte producer (B2-2 Task A6). [ZBonPrintCharacterisationTest] pins the service and the bytes;
 * this pins the route's half: the producer it passes is the real [ZBonFormatter] over the report
 * the route loaded, in German, and it is handed over exactly once. A fake printer invokes the
 * producer and records what it returns, so a route that dropped the call, formatted another report,
 * or formatted something else fails here.
 *
 * [ZBonFormatter.format] does not read its `language` argument today, so a route passing another
 * [Language] would print the same bytes. The second test therefore pins the language at the call
 * site in the route's source, found by file name so that it survives the route's extraction.
 */
@DisplayName("Z-Bon print route hands the printer the formatted Z-Bon")
class ZBonPrintRouteTest {
    /** Invokes the byte producer the route hands over and records the bytes. */
    private class RecordingPrinter : PrinterService {
        val printed = mutableListOf<ByteArray>()

        override suspend fun printZBon(
            format: suspend () -> ByteArray,
            printerId: Long?,
        ): PrinterDto.PrintResponseDto {
            assertEquals(null, printerId, "the route prints to the default printer")
            printed += format()
            return PrinterDto.PrintResponseDto(success = true, message = "recorded", timestamp = "now")
        }

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

        override suspend fun printReferencePrint(
            shopHeader: ShopHeaderData,
            tableNumber: String,
            employeeNumber: String,
            dateTime: String,
            items: List<OrderItemDto.OrderItemResponseDto>,
            printerId: Long?,
        ) = error("not printed in this test")

        override suspend fun testPrint(
            printerLocationId: Long,
            printedBy: String,
        ) = error("not printed in this test")
    }

    @Test
    fun `the Z-Bon print route hands the printer the German ZBonFormatter output of the loaded report`() {
        val printer = RecordingPrinter()
        var zbonId = 0L
        testApp(
            setupData = {
                val admin = TestData.createAdmin()
                val shop = TestData.createShopInfo()
                zbonId = TestData.createZBonFullDay(shop, admin).id.value
                loadKoinModules(module { single<PrinterService> { printer } })
            },
        ) { client ->
            val admin = TestAuthHelper.loginAsAdmin(client)

            val response = client.post("/api/z-bon/$zbonId/print") { bearerAuth(admin) }

            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            assertEquals(1, printer.printed.size, "the route hands the printer exactly one Z-Bon")
            val report = GlobalContext.get().get<ZBonService>().getReportById(zbonId, true)
            val expected = ZBonFormatter().format(report, Language.GERMAN)
            val actual = assertNotNull(printer.printed.single())
            assertTrue(actual.isNotEmpty(), "the producer formatted nothing")
            assertContentEquals(expected, actual, "the route must print the German ZBonFormatter output of the report it loaded")
        }
    }

    @Test
    fun `the Z-Bon print route formats the Z-Bon in German`() {
        val skipped = setOf("build", "bin", "node_modules", ".git", ".gradle", ".claude", ".superpowers")
        val routes =
            SourceRoots.repoRoot
                .walkTopDown()
                .onEnter { it.name !in skipped }
                .filter { it.isFile && it.name == "ZBonRoutes.kt" && "/src/main/kotlin/" in it.invariantSeparatorsPath }
                .toList()
        val source = assertNotNull(routes.singleOrNull(), "expected exactly one ZBonRoutes.kt, found $routes").readText()
        val calls = Regex("""printerService\.printZBon\((.*)\)""").findAll(source).map { it.groupValues[1] }.toList()
        assertEquals(
            listOf("{ zBonFormatter.format(receipt, Language.GERMAN) }"),
            calls,
            "the Z-Bon print must be formatted by ZBonFormatter in German, as before B2-2 A6",
        )
    }
}

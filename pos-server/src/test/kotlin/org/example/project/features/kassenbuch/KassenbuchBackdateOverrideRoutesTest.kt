package org.example.project.features.kassenbuch

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.number
import org.example.project.features.auth.data.TemporarySessionStore
import org.example.project.features.document.domain.models.Document
import org.example.project.features.employee.database.tables.EmployeePermissionGroupTable
import org.example.project.features.employee.database.tables.PermissionGroupTable
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.kassenbuch.data.repository.CashBookRepositoryImpl
import org.example.project.features.kassenbuch.database.tables.KassenbuchHeaderTable
import org.example.project.features.kassenbuch.domain.models.KassenbuchHeader
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A receipt older than the shop's limit can still be booked — with a reason, and on a manager's
 * authority.
 *
 * The limit (default 31 days) catches typos, but a genuine late receipt — a supplier invoice found
 * weeks later — must remain bookable. GoBD accepts late recording when it is documented, so the
 * override demands a reason, stores it on the entry (`late_booking_reason`), and needs
 * `CASHBOOK_BACKDATE`: a manager books directly; anyone else who may post (e.g. ACCOUNTANT holds
 * `CASHBOOK_WRITE` but not the override) gets a 202 and a manager-approval request. A future date
 * and a date more than a year back stay refused even for a manager.
 */
@DisplayName("Kassenbuch — backdating override past the Beleg-age limit")
class KassenbuchBackdateOverrideRoutesTest {
    private val today = currentTimeStamp().date
    private val tooOld = today.minus(DatePeriod(days = 40))

    private fun germanDate(d: LocalDate) = "${d.day.toString().padStart(2, '0')}.${d.month.number.toString().padStart(2, '0')}.${d.year}"

    private class Fixture(
        val tresorId: Long,
        val documentId: Long,
    )

    private fun fixture(uploader: Employee): Fixture {
        val shop = TestData.createShopInfo()
        return transaction {
            val tresor = CashBookRepositoryImpl().create(shop, "Tresor", isFiscal = false, datevAccount = "1010")
            val doc =
                Document.new {
                    this.filename = "late.pdf"
                    this.fileType = "INVOICE"
                    this.filePath = "/nonexistent/late.pdf"
                    this.fileSize = 1024L
                    this.uploadedAt = currentTimeStamp()
                    this.uploadedBy = uploader
                    this.isActive = true
                    this.contentHash = "hash-late-${uploader.employeeNumber}"
                    this.mediaType = "application/pdf"
                }
            Fixture(tresor.id.value, doc.id.value)
        }.also {
            // F59: the 12.00 expense is paid out of what the Tresor holds (Kassenminus guard). The
            // deposit is a second Tresor entry, so the assertions below pick the receipt by its
            // belegtext rather than taking "the only Tresor entry".
            TestData.fundCashBook("100.00", it.tresorId)
        }
    }

    /** The Tresor entries this test's request books — the receipt, never the funding deposit. */
    private fun bookedReceipts(f: Fixture) =
        KassenbuchHeader.find { (KassenbuchHeaderTable.cashBookId eq f.tresorId) and (KassenbuchHeaderTable.belegtext eq RECEIPT_TEXT) }

    private fun body(
        f: Fixture,
        date: LocalDate,
        reason: String?,
    ): String {
        val reasonJson = reason?.let { "\"$it\"" } ?: "null"
        return """{"entryType":"CASH_EXPENSE","lines":[{"vatRate":"19","amount":"12.00"}],""" +
            """"belegDatum":"${germanDate(date)}","belegtext":"$RECEIPT_TEXT","documentId":${f.documentId},""" +
            """"cashBookId":${f.tresorId},"lateBookingReason":$reasonJson}"""
    }

    /** Moves [employee] into the ACCOUNTANT group: may post to the cash book, may not backdate. */
    private fun makeAccountant(employee: Employee) =
        transaction {
            val accountantGroup =
                PermissionGroupTable.selectAll().first { it[PermissionGroupTable.code] == "ACCOUNTANT" }[PermissionGroupTable.id]
            EmployeePermissionGroupTable.update({ EmployeePermissionGroupTable.employee eq employee.id }) {
                it[EmployeePermissionGroupTable.group] = accountantGroup
            }
        }

    @Test
    fun `a manager books a receipt past the limit with a reason, and the reason is stored`() {
        lateinit var f: Fixture
        testApp(setupData = { f = fixture(TestData.createManager()) }) { client ->
            val token = TestAuthHelper.loginAsManager(client)

            val response =
                client.post("/api/v1/kassenbuch") {
                    bearerAuth(token)
                    setBody(TextContent(body(f, tooOld, "Lieferantenrechnung verspaetet erhalten"), ContentType.Application.Json))
                }

            assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
            transaction {
                val header = bookedReceipts(f).single()
                assertEquals(germanDate(tooOld), header.belegDatum, "the receipt keeps its real date")
                assertEquals("Lieferantenrechnung verspaetet erhalten", header.lateBookingReason)
            }
        }
    }

    @Test
    fun `without a reason the receipt past the limit is refused`() {
        lateinit var f: Fixture
        testApp(setupData = { f = fixture(TestData.createManager()) }) { client ->
            val token = TestAuthHelper.loginAsManager(client)

            val response =
                client.post("/api/v1/kassenbuch") {
                    bearerAuth(token)
                    setBody(TextContent(body(f, tooOld, null), ContentType.Application.Json))
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `someone who may post but not backdate gets a manager-approval request`() {
        lateinit var f: Fixture
        testApp(
            setupData = {
                val bookkeeper = TestData.createManager()
                makeAccountant(bookkeeper)
                f = fixture(bookkeeper)
            },
        ) { client ->
            val token = TestAuthHelper.loginAs(client, "manager", deviceId = "backdate-test-device")
            val pendingBefore = TemporarySessionStore.getPendingRequests().size

            val response =
                client.post("/api/v1/kassenbuch") {
                    bearerAuth(token)
                    setBody(TextContent(body(f, tooOld, "Quittung im Fahrzeug gefunden"), ContentType.Application.Json))
                }

            assertEquals(HttpStatusCode.Accepted, response.status, response.bodyAsText())
            val request = TemporarySessionStore.getPendingRequests().last()
            assertEquals(pendingBefore + 1, TemporarySessionStore.getPendingRequests().size)
            assertEquals("BACKDATE_CASH_BOOK_ENTRY", request.operation)
            assertEquals("Quittung im Fahrzeug gefunden", request.details["reason"])
            transaction {
                assertTrue(
                    bookedReceipts(f).empty(),
                    "nothing is booked until a manager approves",
                )
            }
            TemporarySessionStore.removeRequest(request.requestId)
            TemporarySessionStore.removeDeferredAction(request.requestId)
        }
    }

    @Test
    fun `a receipt more than a year back is refused even for a manager`() {
        lateinit var f: Fixture
        testApp(setupData = { f = fixture(TestData.createManager()) }) { client ->
            val token = TestAuthHelper.loginAsManager(client)

            val response =
                client.post("/api/v1/kassenbuch") {
                    bearerAuth(token)
                    setBody(
                        TextContent(body(f, today.minus(DatePeriod(days = 367)), "Tippfehler im Jahr"), ContentType.Application.Json),
                    )
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `a reason on a receipt inside the limit is not stored`() {
        lateinit var f: Fixture
        testApp(setupData = { f = fixture(TestData.createManager()) }) { client ->
            val token = TestAuthHelper.loginAsManager(client)

            val response =
                client.post("/api/v1/kassenbuch") {
                    bearerAuth(token)
                    setBody(TextContent(body(f, today, "nicht noetig"), ContentType.Application.Json))
                }

            assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
            transaction {
                val header = bookedReceipts(f).single()
                assertNull(header.lateBookingReason, "only an actual override carries a late-booking reason")
            }
        }
    }

    private companion object {
        const val RECEIPT_TEXT = "Spaete Quittung"
    }
}

package org.example.project.features.debtor

import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import org.example.project.core.domain.dto.debtor.DebtorDto
import org.example.project.core.error.PosErrorCode
import org.example.project.core.error.ProblemDetail
import org.example.project.features.debtor.domain.models.Debtor
import org.example.project.features.salesinvoice.domain.models.SalesInvoice
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.testutils.testJson
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@DisplayName("Debtor Routes")
class DebtorRoutesTest {
    private fun createRequest(
        name: String = "Musterfirma GmbH",
        debtorNo: String? = null,
    ) = DebtorDto.CreateRequestDto(
        name = name,
        street = "Industriestr. 5",
        zip = "10117",
        city = "Berlin",
        debtorNo = debtorNo,
    )

    @Test
    fun `POST creates a debtor and GET lists it`() =
        testApp(setupData = {
            TestData.createShopInfo()
            TestData.createAdmin()
        }) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)

            val created =
                client
                    .post("/api/debtors") {
                        bearerAuth(token)
                        contentType(ContentType.Application.Json)
                        setBody(createRequest())
                    }.also { assertEquals(HttpStatusCode.Created, it.status) }
                    .body<DebtorDto.ResponseDto>()

            assertEquals("Musterfirma GmbH", created.name)
            assertEquals(0L, created.salesInvoiceCount)

            val list =
                client
                    .get("/api/debtors") { bearerAuth(token) }
                    .also { assertEquals(HttpStatusCode.OK, it.status) }
                    .body<DebtorDto.ListResponseDto>()

            assertEquals(1L, list.total)
            assertEquals(created.id, list.items.single().id)
        }

    @Test
    fun `GET by id returns the debtor and PUT replaces its fields`() =
        testApp(setupData = {
            TestData.createShopInfo()
            TestData.createAdmin()
        }) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val created =
                client
                    .post("/api/debtors") {
                        bearerAuth(token)
                        contentType(ContentType.Application.Json)
                        setBody(createRequest())
                    }.body<DebtorDto.ResponseDto>()

            val fetched =
                client
                    .get("/api/debtors/${created.id}") { bearerAuth(token) }
                    .also { assertEquals(HttpStatusCode.OK, it.status) }
                    .body<DebtorDto.ResponseDto>()
            assertEquals(created.id, fetched.id)

            val updated =
                client
                    .put("/api/debtors/${created.id}") {
                        bearerAuth(token)
                        contentType(ContentType.Application.Json)
                        setBody(DebtorDto.UpdateRequestDto(name = "Musterfirma Handels GmbH", city = "Hamburg"))
                    }.also { assertEquals(HttpStatusCode.OK, it.status) }
                    .body<DebtorDto.ResponseDto>()

            assertEquals("Musterfirma Handels GmbH", updated.name)
            assertEquals("Hamburg", updated.city)
            assertEquals(null, updated.street, "a full replace nulls an omitted optional")
        }

    @Test
    fun `POST with a taken debtor number returns 409`() =
        testApp(setupData = {
            TestData.createShopInfo()
            TestData.createAdmin()
        }) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            client.post("/api/debtors") {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(createRequest(name = "Erste GmbH", debtorNo = "10247"))
            }

            val response =
                client.post("/api/debtors") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(createRequest(name = "Zweite GmbH", debtorNo = "10247"))
                }

            assertEquals(HttpStatusCode.Conflict, response.status, "the service guard reaches HTTP intact")
        }

    @Test
    fun `DELETE removes an unused debtor but refuses an invoiced one`() =
        testApp(setupData = {
            TestData.createSettledBillForInvoicing()
            TestData.createAdmin()
        }) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val unused =
                client
                    .post("/api/debtors") {
                        bearerAuth(token)
                        contentType(ContentType.Application.Json)
                        setBody(createRequest(name = "Tippfehler GmbH"))
                    }.body<DebtorDto.ResponseDto>()

            assertEquals(
                HttpStatusCode.NoContent,
                client.delete("/api/debtors/${unused.id}") { bearerAuth(token) }.status,
            )
            assertEquals(
                HttpStatusCode.NotFound,
                client.get("/api/debtors/${unused.id}") { bearerAuth(token) }.status,
            )

            val invoiced =
                client
                    .post("/api/debtors") {
                        bearerAuth(token)
                        contentType(ContentType.Application.Json)
                        setBody(createRequest(name = "Rechnungsempfänger GmbH"))
                    }.body<DebtorDto.ResponseDto>()
            attachAnInvoiceTo(invoiced.id)

            assertEquals(
                HttpStatusCode.Conflict,
                client.delete("/api/debtors/${invoiced.id}") { bearerAuth(token) }.status,
            )
            transaction { assertNotNull(Debtor.findById(invoiced.id), "a refused delete leaves the row") }
        }

    /**
     * NEW-20: the server half of the e-mail guard. The adminApp form refuses `kaputt` on Save; a client
     * that skips that check (or an older one) must still be refused here, on create and on update, with
     * a field error the form can map back onto the e-mail field. Blank stays allowed: the field is
     * optional.
     */
    @Test
    fun `a malformed e-mail is refused with 400 and a field error, a blank one is accepted`() =
        testApp(setupData = {
            TestData.createShopInfo()
            TestData.createAdmin()
        }) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)

            val refused =
                client.post("/api/debtors") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(createRequest().copy(email = "kaputt"))
                }
            assertEquals(HttpStatusCode.BadRequest, refused.status)
            val problem = testJson.decodeFromString(ProblemDetail.serializer(), refused.bodyAsText())
            assertEquals(PosErrorCode.VALIDATION_FAILED.name, problem.code)
            assertEquals(listOf("email" to "INVALID_FORMAT"), problem.errors?.map { it.field to it.code })
            transaction { assertEquals(0L, Debtor.all().count(), "nothing was written") }

            val created =
                client
                    .post("/api/debtors") {
                        bearerAuth(token)
                        contentType(ContentType.Application.Json)
                        setBody(createRequest().copy(email = "  "))
                    }.also { assertEquals(HttpStatusCode.Created, it.status) }
                    .body<DebtorDto.ResponseDto>()

            val badUpdate =
                client.put("/api/debtors/${created.id}") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(DebtorDto.UpdateRequestDto(name = created.name, email = "info@firma"))
                }
            assertEquals(HttpStatusCode.BadRequest, badUpdate.status)

            val goodUpdate =
                client
                    .put("/api/debtors/${created.id}") {
                        bearerAuth(token)
                        contentType(ContentType.Application.Json)
                        setBody(DebtorDto.UpdateRequestDto(name = created.name, email = "rechnung@firma.de"))
                    }.also { assertEquals(HttpStatusCode.OK, it.status) }
                    .body<DebtorDto.ResponseDto>()
            assertEquals("rechnung@firma.de", goodUpdate.email)
        }

    @Test
    fun `an unauthenticated request is rejected`() =
        testApp(setupData = { TestData.createShopInfo() }) { client ->
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/debtors").status)
        }

    /** Points the seeded bill's draft invoice at [debtorId] so the delete guard has something to see. */
    private fun attachAnInvoiceTo(debtorId: Long) {
        transaction {
            val bill =
                org.example.project.features.bill.domain.models.Bill
                    .all()
                    .first()
            val invoice =
                SalesInvoice.new {
                    this.shop =
                        org.example.project.features.setup.domain.models.ShopInfo
                            .all()
                            .first()
                    this.revenueOrigin = org.example.project.core.domain.enums.salesinvoice.RevenueOrigin.POS_SETTLED
                    this.buyerName = "Rechnungsempfänger GmbH"
                    this.issueDate =
                        org.example.project.utils.formatter
                            .currentTimeStamp()
                    this.serviceDate =
                        org.example.project.utils.formatter
                            .currentTimeStamp()
                    this.billId = EntityID(bill.id.value, org.example.project.features.bill.database.tables.BillTable)
                }
            invoice.debtor = Debtor.findById(debtorId)
            assertNotNull(invoice.debtor, "the guard has something to see only if the link stuck")
        }
    }

    // FO-03: the adminApp hides debtor create/save/delete below DEBTOR_WRITE; the routes must refuse too.
    @Test
    fun `a cashier cannot create, update or delete a debtor`() =
        testApp(setupData = {
            TestData.createShopInfo()
            TestData.createAdmin()
            TestData.createCashier()
        }) { client ->
            val token = TestAuthHelper.loginAsCashier(client)

            val create =
                client.post("/api/debtors") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(createRequest())
                }
            assertEquals(HttpStatusCode.Forbidden, create.status)
            assertEquals(
                HttpStatusCode.Forbidden,
                client
                    .put("/api/debtors/1") {
                        bearerAuth(token)
                        contentType(ContentType.Application.Json)
                        setBody("{}")
                    }.status,
            )
            assertEquals(HttpStatusCode.Forbidden, client.delete("/api/debtors/1") { bearerAuth(token) }.status)
        }
}

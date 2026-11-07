package org.example.project.features.document

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Deleting a document deactivates it and moves its file to .trash, and the generic metadata PUT
 * rewrites its bookkeeping fields. Both were reachable by any authenticated employee while upload,
 * linking and the type-specific metadata PUTs were already MANAGER-gated.
 */
@DisplayName("Document routes — manager gate on destructive and metadata writes")
class DocumentRoutePermissionTest {
    private val metadataBody =
        TextContent("""{"description":"changed"}""", ContentType.Application.Json)

    @Test
    fun cashierCannotDeleteADocument() =
        testApp(
            setupData = {
                TestData.createShopInfo()
                TestData.createAdmin()
                TestData.createCashier()
            },
        ) { client ->
            val token = TestAuthHelper.loginAsCashier(client)
            assertEquals(
                HttpStatusCode.Forbidden,
                client.delete("/api/documents/1") { bearerAuth(token) }.status,
            )
        }

    @Test
    fun cashierCannotUpdateDocumentMetadata() =
        testApp(
            setupData = {
                TestData.createShopInfo()
                TestData.createAdmin()
                TestData.createCashier()
            },
        ) { client ->
            val token = TestAuthHelper.loginAsCashier(client)
            assertEquals(
                HttpStatusCode.Forbidden,
                client
                    .put("/api/documents/1/metadata") {
                        bearerAuth(token)
                        setBody(metadataBody)
                    }.status,
            )
        }

    // The manager must get past the role gate. The document does not exist, so the service throws
    // RESOURCE_NOT_FOUND -> 404. Asserting the exact status (rather than just "not 403") proves the
    // request actually reached the service layer instead of failing for some unrelated reason (e.g.
    // a 500 from a broken route) that would also happen to satisfy a mere assertNotEquals(Forbidden).
    @Test
    fun managerPassesTheGate() =
        testApp(
            setupData = {
                TestData.createShopInfo()
                TestData.createAdmin()
                TestData.createManager()
            },
        ) { client ->
            val token = TestAuthHelper.loginAsManager(client)
            assertEquals(
                HttpStatusCode.NotFound,
                client.delete("/api/documents/999999") { bearerAuth(token) }.status,
            )
        }

    private val invoiceBody =
        TextContent("""{"invoiceNumber":"RE-1"}""", ContentType.Application.Json)

    @Test
    fun cashierCannotCreateAnInvoiceByHand() =
        testApp(
            setupData = {
                TestData.createShopInfo()
                TestData.createAdmin()
                TestData.createCashier()
            },
        ) { client ->
            val token = TestAuthHelper.loginAsCashier(client)
            val response =
                client.post("/api/documents/1/invoice") {
                    bearerAuth(token)
                    setBody(invoiceBody)
                }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    @Test
    fun cashierCannotEditInvoiceSupplierContent() =
        testApp(
            setupData = {
                TestData.createShopInfo()
                TestData.createAdmin()
                TestData.createCashier()
            },
        ) { client ->
            val token = TestAuthHelper.loginAsCashier(client)
            val response =
                client.put("/api/invoices/1") {
                    bearerAuth(token)
                    setBody(invoiceBody)
                }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    // The manager must get past the role gate. The invoice does not exist, so the service throws
    // RESOURCE_NOT_FOUND -> 404. Asserting the exact status (rather than just "not 403") proves the
    // request actually reached the service layer instead of failing for some unrelated reason.
    @Test
    fun managerPassesTheInvoiceGate() =
        testApp(
            setupData = {
                TestData.createShopInfo()
                TestData.createAdmin()
                TestData.createManager()
            },
        ) { client ->
            val token = TestAuthHelper.loginAsManager(client)
            val response =
                client.put("/api/invoices/999999") {
                    bearerAuth(token)
                    setBody(invoiceBody)
                }
            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    // FO-03: the adminApp hides "Beleg hochladen" below DOCUMENT_WRITE; the route must refuse it too.
    @Test
    fun cashierCannotUploadADocument() =
        testApp(
            setupData = {
                TestData.createShopInfo()
                TestData.createAdmin()
                TestData.createCashier()
            },
        ) { client ->
            val token = TestAuthHelper.loginAsCashier(client)
            assertEquals(
                HttpStatusCode.Forbidden,
                client.post("/api/documents/upload") { bearerAuth(token) }.status,
            )
        }
}

package org.example.project.features.kassenbuch

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
import kotlinx.serialization.json.Json
import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.core.domain.enums.tse.TseSigningStatus
import org.example.project.features.document.domain.models.Document
import org.example.project.features.kassenbuch.data.repository.CashBookRepositoryImpl
import org.example.project.features.kassenbuch.domain.models.KassenbuchHeader
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@DisplayName("Kassenbuch PUT /entries/{headerId}/replace-document")
class KassenbuchReplaceDocumentRoutesTest {
    private fun seedDocument(
        filename: String,
        uploader: org.example.project.features.employee.domain.models.Employee,
    ): Document =
        transaction {
            Document.new {
                this.filename = filename
                this.fileType = "INVOICE"
                this.filePath = "/tmp/test/$filename"
                this.fileSize = 1024L
                this.uploadedAt = currentTimeStamp()
                this.uploadedBy = uploader
                this.isActive = true
                this.contentHash = "hash-$filename"
                this.mediaType = "application/pdf"
            }
        }

    private fun seedHeader(
        shop: org.example.project.features.setup.domain.models.ShopInfo,
        employee: org.example.project.features.employee.domain.models.Employee,
        linked: Document?,
        isLocked: Boolean = false,
    ): KassenbuchHeader =
        transaction {
            KassenbuchHeader.new {
                this.shop = shop
                this.cashBook = CashBookRepositoryImpl().fiscalBookFor(shop)
                this.employee = employee
                this.entryType = KassenbuchEntryType.CASH_SALE
                this.belegNr = "2026-04-0001"
                this.belegDatum = "13.04.2026"
                this.belegtext = "Route test entry"
                this.waehrung = "EUR"
                this.bu = null
                this.gegenkonto = null
                this.kost1 = null
                this.kost2 = null
                this.nachricht = null
                this.totalAmount = BigDecimal("10.0000")
                this.currentBalance = BigDecimal("10.0000")
                this.tseSigningStatus = TseSigningStatus.SIGNED
                this.linkedDocumentId = linked?.id?.value
                this.isLocked = isLocked
                this.createdAt = currentTimeStamp()
            }
        }

    @Test
    fun `PUT replace-document as manager returns 200 and updated detail`() {
        var headerId = 0L
        var oldDocId = 0L
        var newDocId = 0L
        testApp(
            setupData = {
                val shop = TestData.createShopInfo()
                val manager = TestData.createManager()
                val oldDoc = seedDocument("old.pdf", manager)
                val newDoc = seedDocument("new.pdf", manager)
                val header = seedHeader(shop, manager, linked = oldDoc)
                headerId = header.id.value
                oldDocId = oldDoc.id.value
                newDocId = newDoc.id.value
            },
        ) { client ->
            val token = TestAuthHelper.loginAs(client, "manager")
            val response =
                client.put("/api/v1/kassenbuch/entries/$headerId/replace-document") {
                    bearerAuth(token)
                    setBody(TextContent("""{"newDocumentId": $newDocId}""", ContentType.Application.Json))
                }
            assertEquals(HttpStatusCode.OK, response.status)

            // Body is a KassenbuchGroupedDetailDto pointing at the new document
            val json = Json { ignoreUnknownKeys = true }
            val detail = json.decodeFromString<KassenbuchDto.KassenbuchGroupedDetailDto>(response.bodyAsText())
            assertEquals(headerId, detail.headerId)
            assertNotNull(detail.linkedDocument, "Response should include linked document")
            assertEquals(newDocId, detail.linkedDocument!!.id)

            // Soft-replace invariant: old Document row still exists for audit
            transaction {
                val oldReloaded = Document.findById(oldDocId)
                assertNotNull(oldReloaded, "Old document row must still exist after replace")
            }
        }
    }

    @Test
    fun `PUT replace-document as cashier returns 403`() {
        var headerId = 0L
        var newDocId = 0L
        testApp(
            setupData = {
                val shop = TestData.createShopInfo()
                val manager = TestData.createManager()
                TestData.createCashier()
                val oldDoc = seedDocument("old.pdf", manager)
                val newDoc = seedDocument("new.pdf", manager)
                val header = seedHeader(shop, manager, linked = oldDoc)
                headerId = header.id.value
                newDocId = newDoc.id.value
            },
        ) { client ->
            val token = TestAuthHelper.loginAsCashier(client)
            val response =
                client.put("/api/v1/kassenbuch/entries/$headerId/replace-document") {
                    bearerAuth(token)
                    setBody(TextContent("""{"newDocumentId": $newDocId}""", ContentType.Application.Json))
                }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }
    }

    @Test
    fun `PUT replace-document on locked header returns 422`() {
        var headerId = 0L
        var newDocId = 0L
        testApp(
            setupData = {
                val shop = TestData.createShopInfo()
                val manager = TestData.createManager()
                val oldDoc = seedDocument("old.pdf", manager)
                val newDoc = seedDocument("new.pdf", manager)
                val header = seedHeader(shop, manager, linked = oldDoc, isLocked = true)
                headerId = header.id.value
                newDocId = newDoc.id.value
            },
        ) { client ->
            val token = TestAuthHelper.loginAs(client, "manager")
            val response =
                client.put("/api/v1/kassenbuch/entries/$headerId/replace-document") {
                    bearerAuth(token)
                    setBody(TextContent("""{"newDocumentId": $newDocId}""", ContentType.Application.Json))
                }
            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        }
    }

    @Test
    fun `PUT replace-document with unknown header returns 422`() {
        testApp(
            setupData = {
                val manager = TestData.createManager()
                seedDocument("new.pdf", manager)
            },
        ) { client ->
            val token = TestAuthHelper.loginAs(client, "manager")
            val response =
                client.put("/api/v1/kassenbuch/entries/999999/replace-document") {
                    bearerAuth(token)
                    setBody(TextContent("""{"newDocumentId": 1}""", ContentType.Application.Json))
                }
            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }
}

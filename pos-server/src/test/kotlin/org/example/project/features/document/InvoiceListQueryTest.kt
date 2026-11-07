package org.example.project.features.document

import org.example.project.features.document.data.repository.DocumentRepositoryImpl
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals

class InvoiceListQueryTest {
    private val repo = DocumentRepositoryImpl()

    private fun document(
        uploadedBy: UUID,
        type: String = "INVOICE",
    ) = repo.create(
        filename = "beleg-${System.nanoTime()}.pdf",
        fileType = type,
        filePath = "/tmp/${System.nanoTime()}.pdf",
        fileSize = 1024L,
        uploadedAt = currentTimeStamp(),
        uploadedBy = uploadedBy,
        contentHash = "hash-${System.nanoTime()}",
    )

    @Test
    fun `an invoice document with no invoice row is uncaptured`() {
        var admin: UUID? = null
        testApp(
            setupData = {
                TestData.createShopInfo()
                admin = TestData.createAdmin().id.value
            },
        ) {
            transaction {
                document(admin!!)

                assertEquals(1, repo.countUncapturedInvoiceDocuments())
                assertEquals(1, repo.findUncapturedInvoiceDocuments(50, 0).size)
            }
        }
    }

    @Test
    fun `capturing an invoice removes the document from the uncaptured list`() {
        var admin: UUID? = null
        testApp(
            setupData = {
                TestData.createShopInfo()
                admin = TestData.createAdmin().id.value
            },
        ) {
            transaction {
                val doc = document(admin!!)
                repo.createInvoice(sourceDocumentId = doc.id.value, sellerName = "Metro AG")

                assertEquals(0, repo.countUncapturedInvoiceDocuments())
            }
        }
    }

    @Test
    fun `a non-invoice document is never uncaptured work`() {
        var admin: UUID? = null
        testApp(
            setupData = {
                TestData.createShopInfo()
                admin = TestData.createAdmin().id.value
            },
        ) {
            transaction {
                document(admin!!, type = "ZBON_REPORT")
                document(admin!!, type = "EIGENBELEG")

                assertEquals(0, repo.countUncapturedInvoiceDocuments())
            }
        }
    }

    @Test
    fun `review state partitions the invoices`() {
        testApp {
            transaction {
                repo.createInvoice(sellerName = "Unreviewed GmbH")
                repo.createInvoice(sellerName = "Reviewed GmbH").reviewedAt = currentTimeStamp()

                assertEquals(1, repo.countInvoicesByReviewState(reviewed = false))
                assertEquals(1, repo.countInvoicesByReviewState(reviewed = true))
                assertEquals(2, repo.countInvoicesByReviewState(reviewed = null))
            }
        }
    }

    @Test
    fun `a bare XRechnung with no document still appears`() {
        testApp {
            transaction {
                repo.createInvoice(sellerName = "XRechnung GmbH")

                // The whole point: no source document, so a document-keyed list would miss it.
                assertEquals(1, repo.findInvoicesByReviewState(reviewed = false, 50, 0).size)
            }
        }
    }

    @Test
    fun `a soft-deleted document is not uncaptured work`() {
        var admin: UUID? = null
        testApp(
            setupData = {
                TestData.createShopInfo()
                admin = TestData.createAdmin().id.value
            },
        ) {
            transaction {
                val doc = document(admin!!)
                repo.softDelete(doc.id.value)

                assertEquals(0, repo.countUncapturedInvoiceDocuments())
            }
        }
    }

    /**
     * The specific bug `notExists` exists to prevent. `invoice.source_document_id` is deliberately
     * non-unique — one scanned PDF may hold several invoices — so a left join with a null check
     * would return the document once per invoice. Here it must simply not be uncaptured work at all,
     * and it must count once, not twice.
     */
    @Test
    fun `a document holding two invoices is captured, not counted twice`() {
        var admin: UUID? = null
        testApp(
            setupData = {
                TestData.createShopInfo()
                admin = TestData.createAdmin().id.value
            },
        ) {
            transaction {
                val doc = document(admin!!)
                repo.createInvoice(sourceDocumentId = doc.id.value, sellerName = "Metro AG")
                repo.createInvoice(sourceDocumentId = doc.id.value, sellerName = "Metro AG")

                assertEquals(0, repo.countUncapturedInvoiceDocuments())
                assertEquals(0, repo.findUncapturedInvoiceDocuments(50, 0).size)
                // Both invoices are real and both must be listable.
                assertEquals(2, repo.countInvoicesByReviewState(reviewed = false))
            }
        }
    }
}

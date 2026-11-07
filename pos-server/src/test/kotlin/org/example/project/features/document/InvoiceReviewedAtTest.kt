package org.example.project.features.document

import org.example.project.features.document.data.repository.DocumentRepositoryImpl
import org.example.project.features.document.domain.models.Invoice
import org.example.project.testutils.testApp
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Pins `invoice.reviewed_at` — the human-confirmation stamp that drives the Rechnungen buckets.
 *
 * Each assertion is in its **own transaction**: within one, Exposed's identity map can serve back
 * the entity just written rather than what the column actually stored.
 */
class InvoiceReviewedAtTest {
    private val repo = DocumentRepositoryImpl()

    @Test
    fun `a new invoice is unreviewed`() {
        testApp {
            transaction { assertNull(repo.createInvoice(sellerName = "Metro AG").reviewedAt) }
        }
    }

    @Test
    fun `a stamp survives the round trip`() {
        testApp {
            val id = transaction { repo.createInvoice(sellerName = "Metro AG").id.value }
            transaction { Invoice.findById(id)!!.reviewedAt = currentTimeStamp() }
            transaction { assertNotNull(Invoice.findById(id)!!.reviewedAt) }
        }
    }
}

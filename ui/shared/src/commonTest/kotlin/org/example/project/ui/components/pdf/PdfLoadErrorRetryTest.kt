package org.example.project.ui.components.pdf

import kotlin.test.Test
import kotlin.test.assertEquals

class PdfLoadErrorRetryTest {
    @Test
    fun onlyTransientFailuresOfferARetry() {
        val retryable = PdfLoadError.entries.filter { it.isRetryable() }.toSet()
        assertEquals(setOf(PdfLoadError.Network, PdfLoadError.Unauthorized), retryable)
    }

    @Test
    fun aRefusedDocumentOffersNoRetry() {
        // A 403 is the server's decision about this user and this document; asking again gets the same answer.
        assertEquals(false, PdfLoadError.Forbidden.isRetryable())
    }
}

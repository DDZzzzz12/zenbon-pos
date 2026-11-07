package org.example.project.banking.detect

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CamtNamespacesTest {
    @Test
    fun `recognises the German camt 053 001 02 profile`() {
        val uri = "urn:iso:std:iso:20022:tech:xsd:camt.053.001.02"
        assertEquals(uri, CamtNamespaces.statementNamespaceOrNull(uri))
    }

    @Test
    fun `recognises camt 053 001 08`() {
        val uri = "urn:iso:std:iso:20022:tech:xsd:camt.053.001.08"
        assertEquals(uri, CamtNamespaces.statementNamespaceOrNull(uri))
    }

    @Test
    fun `recognises an unlisted minor version of camt 053`() {
        val uri = "urn:iso:std:iso:20022:tech:xsd:camt.053.001.04"
        assertEquals(uri, CamtNamespaces.statementNamespaceOrNull(uri))
    }

    @Test
    fun `rejects camt 052 and camt 054 as the wrong message type`() {
        assertNull(CamtNamespaces.statementNamespaceOrNull("urn:iso:std:iso:20022:tech:xsd:camt.052.001.02"))
        assertNull(CamtNamespaces.statementNamespaceOrNull("urn:iso:std:iso:20022:tech:xsd:camt.054.001.02"))
    }

    @Test
    fun `rejects an unrelated namespace`() {
        assertNull(CamtNamespaces.statementNamespaceOrNull("urn:oasis:names:specification:ubl:schema:xsd:Invoice-2"))
        assertNull(CamtNamespaces.statementNamespaceOrNull(null))
    }

    @Test
    fun `binds the camt prefix to the document's own namespace`() {
        val uri = "urn:iso:std:iso:20022:tech:xsd:camt.053.001.08"
        assertEquals(uri, CamtNamespaces.contextFor(uri).getNamespaceURI("camt"))
    }
}

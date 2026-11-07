package org.example.project.core.presentation.components.docUtil

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ViewableDocumentTest {
    private fun document(mimeType: String) = ViewableDocument(name = "beleg", bytes = byteArrayOf(1), mimeType = mimeType)

    @Test
    fun defaultMimeTypeIsAPdf() {
        assertTrue(ViewableDocument(name = "beleg", bytes = byteArrayOf(1)).isPdf)
    }

    @Test
    fun pdfMimeTypeIsMatchedCaseInsensitivelyAndWithoutParameters() {
        assertTrue(document("APPLICATION/PDF").isPdf)
        assertTrue(document("application/pdf; charset=binary").isPdf)
        assertTrue(document(" application/pdf ").isPdf)
    }

    @Test
    fun otherMimeTypesAreNotPdfs() {
        assertFalse(document("image/jpeg").isPdf)
        assertFalse(document("application/xml").isPdf)
        assertFalse(document("application/pdfx").isPdf)
        assertFalse(document("").isPdf)
    }
}

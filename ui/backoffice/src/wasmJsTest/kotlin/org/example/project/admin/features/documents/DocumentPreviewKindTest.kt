package org.example.project.admin.features.documents

import org.example.project.admin.features.documents.presentation.DocumentPreviewKind
import org.example.project.admin.features.documents.presentation.previewKind
import kotlin.test.Test
import kotlin.test.assertEquals

class DocumentPreviewKindTest {
    @Test
    fun `a pdf and a not-yet-loaded document use the pdf frame`() {
        assertEquals(DocumentPreviewKind.PDF, previewKind("application/pdf"))
        assertEquals(DocumentPreviewKind.PDF, previewKind(null))
    }

    @Test
    fun `an xml, text or image file has no pdf preview`() {
        assertEquals(DocumentPreviewKind.NONE, previewKind("application/xml"))
        assertEquals(DocumentPreviewKind.NONE, previewKind("text/xml"))
        assertEquals(DocumentPreviewKind.NONE, previewKind("text/plain"))
        assertEquals(DocumentPreviewKind.NONE, previewKind("image/png"))
    }
}

package org.example.project.testutils

import org.example.project.features.document.data.service.KassenbuchDocumentsImpl
import org.example.project.features.document.domain.service.DocumentManagementService
import org.example.project.features.document.domain.service.PdfGenerationService
import org.example.project.features.kassenbuch.domain.service.KassenbuchDocuments
import java.lang.reflect.Proxy

/**
 * The production [KassenbuchDocuments] for tests that build the Kassenbuch repository or a fiscal
 * service by hand: the real delegation over [pdfGenerationService] and [documentManagementService],
 * which default to stand-ins failing on any call. The lookups (`documentExists`, `linkedDocumentOf`,
 * `storedZBonReportId`) need neither, so a test that only links documents passes nothing.
 */
fun testKassenbuchDocuments(
    pdfGenerationService: PdfGenerationService = notUsed(),
    documentManagementService: DocumentManagementService = notUsed(),
): KassenbuchDocuments = KassenbuchDocumentsImpl(pdfGenerationService, documentManagementService)

private inline fun <reified T : Any> notUsed(): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
        when (method.name) {
            "toString" -> "NotUsed${T::class.simpleName}"
            "hashCode" -> 0
            "equals" -> false
            else -> throw NotImplementedError("${T::class.simpleName}.${method.name} is not used by this test")
        }
    } as T

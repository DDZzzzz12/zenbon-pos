package org.example.project.infrastructure.security.rules

import io.ktor.http.HttpMethod
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.infrastructure.security.AccessLevel
import org.example.project.infrastructure.security.AccessLevel.Cap
import org.example.project.infrastructure.security.RuleModule

/**
 * Document management (Belege) — `DocumentRoutes.kt`.
 *
 * Reading documents was `Authenticated` — the till lists and opens Belege — so `DOCUMENT_READ`
 * sits in `TERMINAL_DEVICE`. Every write, including `upload`, is manager-gated, so `DOCUMENT_WRITE`
 * sits in `MANAGER`; composeApp is view-only for documents, which matches.
 *
 * Deletion is the RESERVED `DOCUMENT_DELETE`: GoBD retention makes destroying a Beleg dangerous by
 * nature, so Stage 5 will refuse to place it in an operator-created group.
 */
object DocumentRules : RuleModule {
    override val name = "Documents"

    override val pathPrefixes = listOf("/api/documents")

    override val allowedCapabilities = setOf(Capability.DOCUMENT_DELETE, Capability.DOCUMENT_READ, Capability.DOCUMENT_WRITE)

    override val rules: Map<Pair<String, HttpMethod>, AccessLevel> =
        mapOf(
            ("/api/documents/document-types" to HttpMethod.Get) to Cap(Capability.DOCUMENT_READ),
            ("/api/documents/upload" to HttpMethod.Post) to Cap(Capability.DOCUMENT_WRITE),
            ("/api/documents/{id}" to HttpMethod.Get) to Cap(Capability.DOCUMENT_READ),
            ("/api/documents/{id}/file" to HttpMethod.Get) to Cap(Capability.DOCUMENT_READ),
            ("/api/documents" to HttpMethod.Get) to Cap(Capability.DOCUMENT_READ),
            ("/api/documents/{id}" to HttpMethod.Delete) to Cap(Capability.DOCUMENT_DELETE),
            ("/api/documents/statistics" to HttpMethod.Get) to Cap(Capability.DOCUMENT_READ),
            ("/api/documents/{id}/metadata" to HttpMethod.Put) to Cap(Capability.DOCUMENT_WRITE),
            ("/api/documents/{id}/metadata" to HttpMethod.Get) to Cap(Capability.DOCUMENT_READ),
            ("/api/documents/link" to HttpMethod.Post) to Cap(Capability.DOCUMENT_WRITE),
            ("/api/documents/{id}/linked" to HttpMethod.Get) to Cap(Capability.DOCUMENT_READ),
            ("/api/documents/link/{linkId}" to HttpMethod.Delete) to Cap(Capability.DOCUMENT_WRITE),
            ("/api/documents/unlinked" to HttpMethod.Get) to Cap(Capability.DOCUMENT_READ),
            ("/api/documents/{id}/bank-statement-metadata" to HttpMethod.Put) to Cap(Capability.DOCUMENT_WRITE),
            ("/api/documents/export/datev-ap" to HttpMethod.Get) to Cap(Capability.DOCUMENT_READ),
            ("/api/documents/export/datev-ap/statistics" to HttpMethod.Get) to Cap(Capability.DOCUMENT_READ),
            ("/api/documents/{id}/invoice" to HttpMethod.Get) to Cap(Capability.DOCUMENT_READ),
            ("/api/documents/{id}/invoice" to HttpMethod.Post) to Cap(Capability.DOCUMENT_WRITE),
            ("/api/documents/{id}/status" to HttpMethod.Put) to Cap(Capability.DOCUMENT_WRITE),
        )
}
